package com.rikkahub.wordlite;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 节奏 + 词汇族仪器：随包资产 {@code assets/aigc/aigc-rhythm.tsv}（19 行判据）驱动，今天真正给分的那一件。
 *
 * <p><b>为什么是它。</b>三件旧仪器都被同一件事打死：比的两摞文字语域不同（真人侧是已发表论文摘要，
 * 机器侧是模型写的正文），量出来的是"像不像摘要"而不是"像不像机器"。换成同一语域的配对库
 * （DetectRL-X 中文学术域，同一行里就是真人原文 + 同选题模型稿）之后，套话词表两侧都是 0 命中、
 * 整族失效，只有句长节奏与结构这类每段都算得出的稠密量还在亮灯。全部过程见 docs/aigc-rhythm.md。
 *
 * <p><b>口径（与定标侧逐条对齐，不许漂）。</b>打分单位是自然段。每段算 19 个特征值 v（15 节奏 + 4 词汇），
 * {@code z=(v-human_mean)/human_sd}，段分 {@code = Σ weight × sign × z}。R01~R15 对着
 * {@code tools/aigc-rhythm.py}，L02~L05 对着 {@code tools/aigc-lexical.py}（由 {@code tools/aigc-instrument.py}
 * 按行 id 分发）；{@code tests/AigcInstrumentParityRegression} 拿 {@code tests/corpus/aigc-instrument-golden.tsv}
 * 把每段 19 个取值与段分逐条钉死，量台与手机算的不是同一个数就当场红。分句用 {@code 。！？；?!;} 且句号留在句尾，
 * 长度按去掉空白后的字符数算，密度类判据的分母是"中日韩汉字 + 字母 + 数字"的个数。
 *
 * <p><b>刻度。</b>段分不是百分比，也不夹在 0~100 里：它是一条以真人论文正文为零点的相对刻度，
 * 定标库里真人段落分的中位是 0.4496、P90 是 2.958、P99 是 4.5585、最高 7.4108（真人 6737 段）。
 * 这四个数就写在表头的 {@code human_p50/human_p90/human_p99/human_max} 里，界面上必须把前三把真人刻度
 * 一起印出来，让用户看懂自己在哪，而且一律读表，不许把任何一个数抄进代码。
 *
 * <p><b>判可疑段落。</b>段分高于真人 P99，且该段不少于 {@link #MIN_FLAG_CHARS} 有效字、
 * 不少于 {@link #MIN_FLAG_SENTENCES} 句。这条线上的真人误报实测 1.11%（机器段命中 41.9%），必须跟着数一起显示；线本身读表里的
 * {@code human_p99}，代码里不抄这个数。
 */
public final class AigcRhythm {

    /** 随包资产，一台仪器一张表。导出命令 {@code py tools/aigc-instrument-export.py}。 */
    public static final String FILE = "aigc/aigc-rhythm.tsv";
    /**
     * 判据条数：15 条节奏/结构（R01~R15）+ 4 条词汇统计（L02~L05）。
     * 表里多一条少一条都不影响算法，只影响这一条有没有被用上；但 19 条必须全对上才给分——
     * 装到一张旧 15 行表上时 {@link Table#engaged()} 直接判不干活，不许新旧两版混着算。
     */
    public static final int CRITERIA = 19;
    /** 其中节奏/结构族 15 条（R01~R15，下标 1~15），词汇统计族 4 条（L02~L05，下标 16~19）。 */
    public static final int RHYTHM = 15;
    /** 词汇统计族的四条 id，顺序就是它们在 {@link #values} 数组里的先后。 */
    static final String[] LEXICAL = {"L02", "L03", "L04", "L05"};
    /** 期望的表版本；资产里的 version 与它不一致时，界面上印的是资产里那个，不冒充。 */
    public static final String VERSION = "instrument-19-2026-10-12";

    /** 判"可疑段落"的三条线：段分过真人 P99，且段落本身够长够多句（与软肋实测同一口径）。 */
    public static final int MIN_FLAG_CHARS = 120;
    public static final int MIN_FLAG_SENTENCES = 4;
    /** 少于这个句数算不出"节奏"这种东西：分数照给，但界面要说清有多少段落真被量到了。 */
    public static final int MIN_SENTENCES = 3;

    /* ---- 下面每一个数都是实测。出处：artifacts/agent-aigc-rules/instrument-export.json（定标与划线）、
       variants.json 的 R4-x1.25（就是出厂这版）、robustness.json（同一批文本只动"句子怎么切"）；
       过程与样本口径见 docs/aigc-rhythm.md 第 1、3、5 节 ---- */
    /** 定标规模：DetectRL-X 中文学术域配对 2531 对（真人 6737 段 / 机器 6120 段）。 */
    public static final double MEASURED_PAIRS = 2531d;
    /** 定标侧段级 AUC（机器 &gt; 真人）。只节奏族 15 条时 0.9076，补上四条词汇判据之后 0.9295。 */
    public static final double MEASURED_SEGMENT_AUC = 0.9295d;
    /** 定标侧整篇（&ge;1440 有效字）AUC。15 条时 0.9780。 */
    public static final double MEASURED_DOCUMENT_AUC = 0.9816d;
    /** 仓库标注那批（一次没进定标）的外部考试：机器整节直出 / 避套话 / 领域对齐。 */
    public static final double MEASURED_EXTERNAL_RAW = 0.9978d;
    public static final double MEASURED_EXTERNAL_EVASIVE = 0.9971d;
    /** 最难的那一场（同领域同选题的机器稿）：15 条只有 0.7000，现在 0.9602。 */
    public static final double MEASURED_EXTERNAL_DOMAIN = 0.9602d;
    /** 按真人段落分 P99（表里 human_p99=4.5585）划线：真人误报 1.11%，机器段命中 41.9%（定标库口径）。 */
    public static final double MEASURED_FALSE_POSITIVES_PER_MILLE = 11.1d;
    public static final double MEASURED_FALSE_POSITIVES = 0.0111d;
    /**
     * 置信档给这一件仪器用的误报预算。划线本身是每千段 11.1 段，预算仍是 20——
     * 实测误报从 10.2 涨到 11.1，预算不跟着往上抬，余量薄一点是诚实的方向。
     */
    public static final double FALSE_POSITIVE_BUDGET_PER_MILLE = 20.0d;
    /**
     * 软肋一：只把相邻两句并成一句、用词一个字不改，机器段命中从 41.9% 掉到 21.8%，
     * 那一刀下真人误报 0.45%（variants.json 出厂那一档；15 条时代这一招能从 57.5% 打到 3.3%）。
     */
    public static final double MEASURED_HIT_UNDISTURBED = 0.419d;
    public static final double MEASURED_HIT_AFTER_MERGE = 0.218d;
    public static final double MEASURED_FALSE_POSITIVES_AFTER_MERGE = 0.0045d;
    /**
     * 软肋二：把句子切成整齐等长。同一批样本（robustness.json：1810 真人段 / 1434 机器段）未扰动误报 0.99%，
     * 切成等长升到 4.12%、机器命中 33.8%、AUC 0.8421。旧 15 条同一招是 18.79%——这条软肋被词汇判据压浅了，
     * 但没消失，报告里的提示照旧要写。
     */
    public static final double MEASURED_FALSE_POSITIVES_UNDISTURBED = 0.0099d;
    public static final double MEASURED_FALSE_POSITIVES_AFTER_UNIFORM = 0.0412d;
    public static final double MEASURED_HIT_AFTER_UNIFORM = 0.338d;
    public static final double MEASURED_AUC_AFTER_UNIFORM = 0.8421d;
    /** 段长敏感性：真人段在 200~300 / 300~450 / 450~700 字三档的均分，没有"越长越像机器"这件事。 */
    public static final double MEASURED_HUMAN_MEAN_200_300 = 0.722d;
    public static final double MEASURED_HUMAN_MEAN_300_450 = 0.228d;
    public static final double MEASURED_HUMAN_MEAN_450_700 = -0.191d;

    /** 界面与报告里那两句软肋提示，一句话级别，细节在 docs/aigc-rhythm.md 第 5 节。 */
    public static final String WEAK_UNDETECTED = "可疑字数为 0 只说明没发现，不等于没有机器生成："
            + "把相邻两句并成一句（用词一个字都不改）仍能打掉一半命中（实测机器段命中从 41.9% 掉到 21.8%）。";
    public static final String WEAK_LAYOUT = "句子被排成整齐等长时（列表式排版、OCR 质量差的 PDF 正文）"
            + "真人误报会升到 4.1%（同一批样本未扰动是 0.99%，旧版 15 条判据同一招能顶到 18.79%），"
            + "这类来源的读数要打折看。";
    /** 表里没装载时给界面看的那一句：说清是仪器没装上，而不是这份稿子干净。 */
    public static final String NOT_LOADED = "判据表未装载（缺 " + FILE + "），本机算不出倾向指数";

    private static final String SENT_END = "。！？；?!;";
    private static final String CLAUSE_SEP = "，、：:；;（）()“”\"'‘’";
    private static final String PUNCT_GAP = SENT_END + "，、：；";
    private static final String[] CONNECTIVES = {"因此", "所以", "并且", "而且", "同时", "此外",
            "其次", "首先", "然后", "接着", "可见", "综上"};

    /** 一条判据：真人基线 + 方向 + 权重 + 这一条单独的判别力。 */
    public static final class Criterion {
        public String id = "", name = "";
        public double mean, sd = 1d, weight, auc, humanP50, humanP90;
        public int sign = 1, feature = -1, nHuman;

        /** 这一条把这段往"机器"方向推了多少（正=更像机器）。 */
        public double contribution(double value) {
            if (feature < 0) return 0d;
            double safe = sd == 0d ? 1d : sd;
            return weight * sign * ((value - mean) / safe);
        }
    }

    /** 一个自然段的读数：分数、覆盖情况、贡献最大的几条判据。 */
    public static final class Reading {
        public int start, end;
        public int sentences;                 // 分句口径见类注释
        public int validChars;                // 汉字+字母+数字个数
        public boolean measured = true;       // 表没装载时为 false
        public boolean spoke;                 // 句数够算节奏（>= MIN_SENTENCES）
        public double score;                  // Σ weight × sign × z
        public boolean suspicious;            // 过可疑线：> 真人 P99 且够长够多句
        public int triggers;                // 这段里有几条判据往机器方向亮灯（密度的分子）
        public final double[] values = new double[CRITERIA];
        public final ArrayList<String> top = new ArrayList<String>(); // 贡献最大的判据中文名

        public String note = "";
    }

    private static final class Row2 {
        final Criterion criterion;
        final double value;
        Row2(Criterion c, double v) { criterion = c; value = v; }
    }

    private static Table active;
    private static boolean loadAttempted;

    /** 装载好的判据表：19 行（R01~R15 + L02~L05）+ 真人段落分的三个分位 + 表头注释里的实测出处。 */
    public static final class Table {
        public String version = "", calibration = "", caveat = "", external = "", lexDropped = "";
        public double humanP50 = Double.NaN, humanP90 = Double.NaN, humanP99 = Double.NaN;
        public double humanMax = Double.NaN, machineP50 = Double.NaN;
        public double segmentAuc = Double.NaN, documentAuc = Double.NaN, pairs = Double.NaN;
        /** 表头 {@code external=raw:..|evasive:..|domain:..} 拆开的三场外部考试。 */
        public double externalRaw = Double.NaN, externalEvasive = Double.NaN, externalDomain = Double.NaN;
        /** 表头 {@code false_positive_pct} / {@code machine_hit_pct}：划线那一档的真人误报与机器段命中。 */
        public double falsePositive = Double.NaN, machineHit = Double.NaN;
        /** 表头 {@code lexical_factor}：词汇族倍率已经乘进 weight 里，Java 侧只当出处看一眼。 */
        public double lexicalFactor = Double.NaN;
        /** 表头 {@code gate_min_chars} / {@code gate_min_sentences}：判可疑那两条规模门槛，缺了用常量。 */
        public int gateChars = -1, gateSentences = -1;
        public final ArrayList<Criterion> criteria = new ArrayList<Criterion>();
        /** 表里能对上 R01~R15、L02~L05 这 19 个 id 的行数。对不上的行按 0 权重跳过，不会偷偷加分。 */
        public int matched;

        public boolean engaged() {
            if (criteria.isEmpty() || matched < CRITERIA) return false;
            if (Double.isNaN(humanP99)) return false;
            for (int i = 0; i < criteria.size(); i++) if (criteria.get(i).weight > 0d) return true;
            return false;
        }
    }

    private AigcRhythm() { }

    public static Table current() {
        return active;
    }

    /** 这件仪器今天能不能用：表装上了、19 条判据都对上了、真人分位齐了。 */
    public static boolean engaged() {
        return active != null && active.engaged();
    }

    /** 表没装载时返回空串：宁可不写版本号，也不许把代码里的期望值当成资产里真有的东西。 */
    public static String version() {
        return active == null ? "" : active.version;
    }

    public static double humanP50() { return active == null || !active.engaged() ? Double.NaN : active.humanP50; }
    public static double humanP90() { return active == null || !active.engaged() ? Double.NaN : active.humanP90; }
    public static double humanP99() { return active == null || !active.engaged() ? Double.NaN : active.humanP99; }
    public static double machineP50() { return active == null || !active.engaged() ? Double.NaN : active.machineP50; }

    /** 主机回归与打包检查用：装载一份表，返回装载到的条数。 */
    public static Table install(InputStream table) throws IOException {
        Table parsed = parse(table);
        active = parsed;
        loadAttempted = true;
        return parsed;
    }

    /** 应用启动路径：缺文件不崩，只是这件仪器不干活。 */
    public static void loadFromAssets(android.content.Context context) {
        if (loadAttempted || active != null) return;
        loadAttempted = true;
        try {
            install(context.getAssets().open(FILE));
        } catch (Exception missing) {
            active = null;
            android.util.Log.w("WordliteAigc", "rhythm table unreadable: " + missing);
        }
    }

    /** 主机回归用：让下一次 loadFromAssets 真的重读。 */
    public static void resetLoadAttempt() {
        loadAttempted = false;
    }

    public static void uninstall() {
        active = null;
    }

    public static ArrayList<Criterion> criteria() {
        ArrayList<Criterion> out = new ArrayList<Criterion>();
        if (active != null) out.addAll(active.criteria);
        return out;
    }

    /**
    /**
     * 每条判据一句改法。写的是"这一条为什么亮灯、下笔该怎么动"，供降重工单与报告卡直接用。
     * 方向照实测：sign 为负的那几条是真人更高（缺数据、缺引用、句子太齐、标点来来回回那几种），
     * sign 为正的是机器更高（句子切得太碎、连接词起句太多、句首起手式太花、字面词组几乎不重样）。
     * 传 id（R01/L02…）和传表里的中文名都认；两件事都必须落到 {@link #ids} 的同一格上。
     */
    public static String adviceOf(String idOrName) {
        if (idOrName == null) return "";
        int slot = slotOf(idOrName);
        if (slot > 0) return ADVICE[slot - 1];
        // 报告卡上递给这里的是判据中文名，不是 id：先按表找到那一格，再回到同一条改法。
        if (active != null)
            for (int i = 0; i < active.criteria.size(); i++) {
                Criterion criterion = active.criteria.get(i);
                if (idOrName.equals(criterion.name)
                        && criterion.feature >= 1 && criterion.feature <= CRITERIA)
                    return ADVICE[criterion.feature - 1];
            }
        return "";
    }

    /**
     * 判据 id → 特征槽位（1 基）。槽位就是 {@link #ids} 里的位置，也是 {@link Reading#values} 的下标 +1：
     * <b>与表里的行序无关</b>，表把 L05 放在第一行照样算得对。认不出的 id 返回 -1，那一行按 0 权重跳过。
     */
    private static int slotOf(String id) {
        if (id == null) return -1;
        String key = id.trim();
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(key)) return i + 1;
        return -1;
    }

    /** 19 个槽位；顺序与 {@link Reading#values} 一致，与表里的行序无关。 */
    private static final String[] ids = {
        "R01", "R02", "R03", "R04", "R05", "R06", "R07", "R08",
        "R09", "R10", "R11", "R12", "R13", "R14", "R15",
        "L02", "L03", "L04", "L05",
    };

    private static final String[] ADVICE = {
        "连着几句一样长，把其中一句拆短或把两句并长",
        "相邻两句字数几乎相同，改一句的长度",
        "连续三句以上差不多长，挑一句改短",
        "整段没有短句，补一两句短的",
        "长句成串，挑一句拆成两三句",
        "逗号之间的片段长短太齐，长短拉开",
        "每句顿逗号数差不多，让有的句子多几个、有的少几个",
        "标点之间的间隔太均匀，改几处断句",
        "句首起手式换得太勤，留几个自然重复的",
        "句子长度一个接一个地照着来，打乱顺序",
        "并列项排得整整齐齐，拆掉一两项",
        "这一段没有文献编号，把出处补上",
        "结论没有落到数字上，给出实测值",
        "很多句子用因此、此外、综上开头，去掉一半",
        "同样的内容句子切得太多，适当合并",
        "整段几乎不重样地换说法，术语和对象名该重复就重复回来",
        "一次性的说法堆成段，把关键术语在原处再说一遍",
        "用字太平均，补进专名、型号、地名与量值这些只有这份研究才有的字",
        "标点来来回回只有逗号句号，该用分号、冒号、破折号、括号的地方用起来",
    };

    /** 按 id 找那条判据的中文名（可疑段落列表上写的是它）。找不到就回原 id。 */
    public static String nameOf(String id) {
        if (active != null && id != null)
            for (int i = 0; i < active.criteria.size(); i++)
                if (id.equals(active.criteria.get(i).id)) return active.criteria.get(i).name;
        return id == null ? "" : id;
    }

    /** 判据表解析：{@code # key=value} 注释 + 表头 + 数据行。任一行坏掉只丢那一行，不整表作废。 */
    private static Table parse(InputStream stream) throws IOException {
        Table table = new Table();
        if (stream == null) return table;
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, Charset.forName("UTF-8")));
        String[] header = null;
        String line;
        int overflow = 0;
        while ((line = reader.readLine()) != null) {
            if (line.length() == 0) continue;
            if (line.charAt(0) == '#') {
                comment(table, line.substring(1).trim());
                continue;
            }
            String[] cells = line.split("\t", -1);
            if (header == null) {
                header = cells;
                continue;
            }
            if (overflow++ > 4096) break;
            Criterion criterion = row(header, cells);
            if (criterion == null) continue;
            table.criteria.add(criterion);
            if (criterion.feature >= 0) table.matched++;
        }
        reader.close();
        return table;
    }

    /**
     * 表头注释。写法与 {@code tools/aigc-instrument.py} 的 {@code read()} 一致：一行里可以塞好几对
     * {@code key=value}，用空格分开（{@code # human_p50=0.4496 human_p90=2.958 human_p99=4.5585
     * human_max=7.4108} 就是这种行）。认不出的键与不带等号的说明行一律忽略，不整表作废。
     */
    private static void comment(Table table, String body) {
        if (body.length() == 0) return;
        String[] tokens = body.split("\\s+");
        int pairs = 0;
        for (int i = 0; i < tokens.length; i++) {
            int equals = tokens[i].indexOf('=');
            if (equals <= 0) continue;
            String key = tokens[i].substring(0, equals).trim();
            String rest = tokens[i].substring(equals + 1);
            // 值里带空格的自由文本只有 calibration 一处（"DetectRL-X中文学术域2531对 human=6737 machine=..."），
            // 它只可能出现在行首第一个键上，一路吃到行尾。别的一律逐对取——external 那种 a:1|b:2|c:3
            // 必须按对读，整行吞进来会把 domain:0.9602 后面挂的 external_human=113 一起算进数里。
            if (i == 0 && "calibration".equals(key) && body.length() > tokens[i].length()) {
                StringBuilder tail = new StringBuilder(rest);
                for (int k = 1; k < tokens.length; k++) tail.append(' ').append(tokens[k]);
                put(table, key, tail.toString());
                return;
            }
            put(table, key, rest);
            pairs++;
        }
        if (pairs == 0 && body.contains("未验证")) {
            int at = body.indexOf("未验证：");
            table.caveat = at >= 0 ? body.substring(at) : body;
        }
    }

    /** 认得的键才落到表上； {@code external} 那一串 {@code raw:0.9978|evasive:...} 拆成三个数。 */
    private static void put(Table table, String key, String value) {
        double number = number(value);
        if ("version".equals(key)) table.version = value;
        else if ("human_p50".equals(key)) table.humanP50 = number;
        else if ("human_p90".equals(key)) table.humanP90 = number;
        else if ("human_p99".equals(key)) table.humanP99 = number;
        else if ("human_max".equals(key)) table.humanMax = number;
        else if ("machine_p50".equals(key)) table.machineP50 = number;
        else if ("auc_segment".equals(key)) table.segmentAuc = number;
        else if ("auc_document".equals(key)) table.documentAuc = number;
        else if ("false_positive_pct".equals(key)) table.falsePositive = number;
        else if ("machine_hit_pct".equals(key)) table.machineHit = number;
        else if ("lexical_factor".equals(key)) table.lexicalFactor = number;
        else if ("gate_min_chars".equals(key)) table.gateChars = (int) number(value);
        else if ("gate_min_sentences".equals(key)) table.gateSentences = (int) number(value);
        else if ("calibration".equals(key)) {
            table.calibration = value;
            table.pairs = digitsBefore(value, "对");   // "DetectRL-X中文学术域2531对" -> 2531
        } else if ("external".equals(key)) {
            table.external = value;
            table.externalRaw = number(after(value, "raw:", "|"));
            table.externalEvasive = number(after(value, "evasive:", "|"));
            table.externalDomain = number(after(value, "domain:", " "));
        } else if ("lex_dropped".equals(key)) {
            table.lexDropped = value;
        } else if (key.startsWith("定标")) {
            table.calibration = value;
            table.pairs = number(after(value, "配对 ", " 对"));
            table.segmentAuc = number(after(value, "段级AUC=", null));
            table.documentAuc = number(after(value, "整篇AUC=", null));
        }
    }

    private static Criterion row(String[] header, String[] cells) {
        Criterion out = new Criterion();
        for (int i = 0; i < header.length && i < cells.length; i++) {
            String key = header[i].trim(), value = cells[i].trim();
            if ("id".equals(key)) out.id = value;
            else if ("name".equals(key)) out.name = value;
            else if ("human_mean".equals(key)) out.mean = number(value);
            else if ("human_sd".equals(key)) out.sd = number(value);
            else if ("sign".equals(key)) out.sign = number(value) < 0d ? -1 : 1;
            else if ("weight".equals(key)) out.weight = number(value);
            else if ("fit_auc".equals(key)) out.auc = number(value);
            else if ("human_p50".equals(key)) out.humanP50 = number(value);
            else if ("human_p90".equals(key)) out.humanP90 = number(value);
            else if ("n_human".equals(key)) out.nHuman = (int) number(value);
        }
        if (out.id.length() == 0 || out.name.length() == 0) return null;
        if (Double.isNaN(out.mean) || Double.isNaN(out.weight)) return null;
        out.feature = featureIndex(out.id);
        return out;
    }

    /**
     * R01~R15 -> 下标 1~15，L02~L05 -> 下标 16~19；对不上返回 -1（这一行按 0 权重跳过）。
     * 槽位只有 {@link #ids} 一份口径（与表里的行序无关）：改判据只改那张表与 {@link #values}，不许再抄一份映射。
     * 词表类判据（ai-tells 那批）不在这张表里，也不占下标——它们只列证据，不计分。
     */
    private static int featureIndex(String id) {
        return slotOf(id);   // 槽位只有 ids 一份口径：改判据只改那张表和 values，别再抄第二份映射
    }

    private static double number(String raw) {
        if (raw == null) return Double.NaN;
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception bad) {
            return Double.NaN;
        }
    }

    /** 取 {@code from} 与 {@code to} 之间那段文字，取不到返回 null。 */
    private static String after(String text, String from, String to) {
        int start = text.indexOf(from);
        if (start < 0) return null;
        start += from.length();
        int end = to == null ? text.length() : text.indexOf(to, start);
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }

    /** 取 {@code marker} 前面紧挨着的那串数字（"中文学术域2531对" -&gt; 2531）；没有就给 NaN。 */
    private static double digitsBefore(String text, String marker) {
        int at = text == null ? -1 : text.indexOf(marker);
        if (at <= 0) return Double.NaN;
        int start = at;
        while (start > 0 && Character.isDigit(text.charAt(start - 1))) start--;
        return start == at ? Double.NaN : number(text.substring(start, at));
    }

    /** 出处里那一条主句：版本、定标规模、两侧 AUC、划线误报率，一个数都不多写。 */
    public static String describe() {
        Table table = active;
        if (table == null || !table.engaged()) return NOT_LOADED;
        StringBuilder out = new StringBuilder(versionOf(table)).append(" · ");
        out.append("定标 ").append(pairsOf(table)).append(" 对学术配对 · ");
        out.append("段级 AUC ").append(auc(table.segmentAuc, MEASURED_SEGMENT_AUC))
                .append(" / 整篇 ").append(auc(table.documentAuc, MEASURED_DOCUMENT_AUC))
                .append(" · 划线真人误报 ").append(percent(falsePositivePerMille() / 1000d));
        return out.toString();
    }

    /**
     * 划线那一档的真人误报率，单位是每千段。表头写了 {@code false_positive_pct} 就读表，
     * 读不到才回落到出厂常量——换一版定标表时界面上的误报率必须跟着走，不许停在代码里那个数。
     */
    public static double falsePositivePerMille() {
        Table table = active;
        return table == null || Double.isNaN(table.falsePositive)
                ? MEASURED_FALSE_POSITIVES_PER_MILLE : table.falsePositive * 10d;
    }

    /** 同一条线上的机器段命中率（0~1）。同样优先读表。 */
    public static double machineHitRate() {
        Table table = active;
        return table == null || Double.isNaN(table.machineHit)
                ? MEASURED_HIT_UNDISTURBED : table.machineHit / 100d;
    }

    /** 界面上一句限定：这一件仪器还没验过什么，不许含糊。 */
    public static String caveatLine() {
        Table table = active;
        String caveat = table == null ? "" : table.caveat;
        if (caveat.startsWith("未验证：")) caveat = caveat.substring("未验证：".length());
        return caveat.length() > 0 ? caveat : "跨学科与学生学位论文语域未单独验证；刻意模仿人类节奏的稿子可能测不出";
    }

    /** 置信档只认这一件仪器自带的实测读数：AUC 用段级那一档，误报用划线那一档。 */
    public static AigcConfidence confidence() {
        double auc = active != null && !Double.isNaN(active.segmentAuc)
                ? active.segmentAuc : MEASURED_SEGMENT_AUC;
        return AigcConfidence.of(auc, falsePositivePerMille(),
                FALSE_POSITIVE_BUDGET_PER_MILLE, engaged());
    }

    /** 判可疑的字数门槛：表头写了 {@code gate_min_chars} 就读表，读不到才用 {@link #MIN_FLAG_CHARS}。 */
    public static int flagChars() {
        Table table = active;
        return table != null && table.gateChars > 0 ? table.gateChars : MIN_FLAG_CHARS;
    }

    /** 判可疑的句数门槛：同上，优先读表。 */
    public static int flagSentences() {
        Table table = active;
        return table != null && table.gateSentences > 0 ? table.gateSentences : MIN_FLAG_SENTENCES;
    }

    /* ---- 绝对分数线跨语域的迁移实测（2026-10-12，主机侧量的，不是定标台给的）----
       复现：AigcRegression.corpusTransfer() 每次跑都重算一遍并钉死；口径与出厂判定完全一致
       （段分过真人 P99 且整段 >=120 有效字 >=4 句），语料是 tests/corpus 那批带标注文件。
       过程与结论写在 docs/aigc-detection.md 第十二节。 ---- */
    /** 真人侧：real-prose + 逐字摘录 + 真人润色三份，够判定规模的段落数与过线数。 */
    public static final int MEASURED_CROSS_HUMAN_SEGMENTS = 59;
    public static final int MEASURED_CROSS_HUMAN_FLAGGED = 9;
    /** 机器侧：直出 / 避套话 / 领域对齐三份，够判定规模的段落全过线。 */
    public static final int MEASURED_CROSS_MACHINE_SEGMENTS = 40;
    public static final double MEASURED_CROSS_MACHINE_HITS = 1.0d;
    /** 同一批语料上的段级 AUC（机器 &gt; 真人）：排序分得开，问题只出在那条绝对线上。 */
    public static final double MEASURED_CROSS_AUC = 0.9948d;
    /** 出处行末尾跟着的一句：绝对线不跨语域，跨过去就只剩排序可用。 */
    public static final String OUT_OF_DOMAIN = "绝对分数线只在定标那批真人论文正文上量过（真人误报 "
            + percent(MEASURED_FALSE_POSITIVES) + "）；搬到仓库标注的另一批真人语料上重跑同一条线，"
            + MEASURED_CROSS_HUMAN_SEGMENTS + " 段真人段落里过线 " + MEASURED_CROSS_HUMAN_FLAGGED
            + " 段，同一批里 " + MEASURED_CROSS_MACHINE_SEGMENTS + " 段机器稿全过线，段级 AUC "
            + String.format(Locale.US, "%.3f", Double.valueOf(MEASURED_CROSS_AUC))
            + "——排序还分得开，绝对分换一批语料就不作数了";

    /** 整段读数。文本按原文（不归一化）传进来，分句与长度口径才和 Python 侧一致。 */
    public static Reading measure(String text, int from, int to) {
        Reading out = new Reading();
        out.start = Math.max(0, from);
        out.end = Math.min(text == null ? 0 : text.length(), Math.max(out.start, to));
        String raw = text == null || out.end <= out.start ? "" : text.substring(out.start, out.end);
        Profile profile = new Profile(raw);
        out.sentences = profile.sentenceCount();
        out.validChars = profile.validChars;
        values(profile, out.values);
        Table table = active;
        if (table == null || !table.engaged()) {
            out.measured = false;
            out.note = NOT_LOADED;
            return out;
        }
        out.spoke = out.sentences >= MIN_SENTENCES;
        double score = 0d;
        ArrayList<Row2> positives = new ArrayList<Row2>();
        for (int i = 0; i < table.criteria.size(); i++) {
            Criterion criterion = table.criteria.get(i);
            if (criterion.feature < 0) continue;
            double value = out.values[criterion.feature - 1];
            double contribution = criterion.contribution(value);
            score += contribution;
            if (contribution > 0d) positives.add(new Row2(criterion, contribution));
        }
        out.score = score;
        out.triggers = positives.size();
        out.suspicious = score > table.humanP99 && out.validChars >= flagChars()
                && out.sentences >= flagSentences();
        // 贡献最大的几条判据：界面上"这段凭什么可疑"读的就是它。
        java.util.Collections.sort(positives, new java.util.Comparator<Row2>() {
            public int compare(Row2 a, Row2 b) {
                return Double.compare(b.value, a.value);
            }
        });
        for (int i = 0; i < positives.size() && i < 3; i++)
            out.top.add(positives.get(i).criterion.name);
        return out;
    }

    private static String versionOf(Table table) {
        return table.version != null && table.version.length() > 0 ? table.version : VERSION;
    }

    private static String pairsOf(Table table) {
        return Double.isNaN(table.pairs) ? String.valueOf((long) MEASURED_PAIRS)
                : String.valueOf((long) table.pairs);
    }

    private static String auc(double measured, double fallback) {
        double value = Double.isNaN(measured) ? fallback : measured;
        return String.format(Locale.US, "%.3f", Double.valueOf(value));
    }

    private static String percent(double share) {
        return String.format(Locale.US, "%.2f%%", Double.valueOf(share * 100d));
    }

    /** 一个自然段算好的原始材料：分句、分句长度、顿逗号数、标点位置。 */
    private static final class Profile {
        final String clean;
        final ArrayList<String> sentences = new ArrayList<String>();
        final int[] sentenceLengths;
        final int[] commaCounts;
        final int[] clauseLengths;
        final int[] gapPositions;
        final int validChars;

        Profile(String raw) {
            clean = clean(raw);
            validChars = validLen(raw);
            ArrayList<String> parts = sents(raw);
            int clauses = 0;
            for (int i = 0; i < parts.size(); i++) clauses += countClauses(parts.get(i));
            sentences.addAll(parts);
            sentenceLengths = new int[parts.size()];
            commaCounts = new int[parts.size()];
            clauseLengths = new int[clauses];
            int at = 0;
            for (int i = 0; i < parts.size(); i++) {
                String sentence = parts.get(i);
                sentenceLengths[i] = codeLen(sentence);
                commaCounts[i] = commas(sentence);
                int[] lengths = clauseLengths(sentence);
                for (int k = 0; k < lengths.length; k++) clauseLengths[at++] = lengths[k];
            }
            // 位置按**码点**编号，不按 UTF-16 下标：Python 那边 enumerate(字符串) 数的是码点，
            // 段里只要有一个补充平面字符（emoji），按 charAt 编号就会把后面的间隔整体顶宽一格。
            int gaps = 0;
            for (int i = 0; i < clean.length(); ) {
                int ch = Character.codePointAt(clean, i);
                if (ch < 0x10000 && indexOf(PUNCT_GAP, (char) ch) >= 0) gaps++;
                i += Character.charCount(ch);
            }
            gapPositions = new int[gaps];
            at = 0;
            for (int i = 0, cp = 0; i < clean.length(); cp++) {
                int ch = Character.codePointAt(clean, i);
                if (ch < 0x10000 && indexOf(PUNCT_GAP, (char) ch) >= 0) gapPositions[at++] = cp;
                i += Character.charCount(ch);
            }
        }

        int sentenceCount() {
            return sentences.size();
        }
    }

    private static int countClauses(String sentence) {
        int out = 0;
        int pending = 0;
        for (int i = 0; i < sentence.length(); i++) {
            char c = sentence.charAt(i);
            if (indexOf(CLAUSE_SEP, c) >= 0) {
                if (pending > 0) out++;
                pending = 0;
            } else if (indexOf(CLAUSE_SEP, c) < 0) pending++;
        }
        return pending > 0 ? out + 1 : out;
    }

    /**
     * 19 个特征值：下标 0~14 是 R01~R15，15~18 是 L02~L05。节奏那 15 条逐条对着
     * {@code tools/aigc-rhythm.py} 写，包括那些看起来多余的边界（句数不足 3 句时给 0、
     * 均值不足 4 个分句时给 0）；词汇那 4 条直接交给 {@link AigcLexical}，口径对着
     * {@code tools/aigc-lexical.py}。两侧算不出两个数，由黄金样本回归兜底。
     */
    static void values(Profile p, double[] out) {
        java.util.Arrays.fill(out, 0d);
        out[15] = AigcLexical.typeRatio(p.clean);
        out[16] = AigcLexical.hapaxRatio(p.clean);
        out[17] = AigcLexical.charEntropy(p.clean);
        out[18] = AigcLexical.punctEntropy(p.clean);
        int[] lengths = p.sentenceLengths;
        double meanLength = mean(lengths);
        out[0] = lengths.length >= 3 && meanLength > 0d ? stdev(lengths) / meanLength : 0d;
        if (lengths.length >= 3 && meanLength > 0d) {
            double[] steps = new double[lengths.length - 1];
            for (int i = 0; i < steps.length; i++) steps[i] = Math.abs(lengths[i + 1] - lengths[i]);
            out[1] = mean(steps) / meanLength;
        }
        if (lengths.length > 0) {
            int best = 1, run = 1;
            for (int i = 1; i < lengths.length; i++) {
                int lo = Math.min(lengths[i], lengths[i - 1]);
                int hi = Math.max(lengths[i], lengths[i - 1]);
                run = lo >= 0.8d * hi ? run + 1 : 1;
                if (run > best) best = run;
            }
            out[2] = best / (double) lengths.length;
        }
        int shortOnes = 0, longOnes = 0;
        for (int i = 0; i < lengths.length; i++) {
            if (lengths[i] <= 12) shortOnes++;
            if (lengths[i] >= 60) longOnes++;
        }
        if (lengths.length > 0) {
            out[3] = shortOnes / (double) lengths.length;
            out[4] = longOnes / (double) lengths.length;
        }
        double meanClause = mean(p.clauseLengths);
        out[5] = p.clauseLengths.length >= 4 && meanClause > 0d ? stdev(p.clauseLengths) / meanClause : 0d;
        double meanComma = mean(p.commaCounts);
        out[6] = p.commaCounts.length > 0 && meanComma > 0d ? stdev(p.commaCounts) / meanComma : 0d;
        int[] gaps = p.gapPositions;
        if (gaps.length >= 4) {
            double[] steps = new double[gaps.length - 1];
            for (int i = 0; i < steps.length; i++) steps[i] = gaps[i + 1] - gaps[i];
            double meanStep = mean(steps);
            out[7] = meanStep > 0d ? stdev(steps) / meanStep : 0d;
        }
        if (p.sentences.size() >= 3) {
            ArrayList<String> heads = new ArrayList<String>();
            for (int i = 0; i < p.sentences.size(); i++) heads.add(head2(p.sentences.get(i)));
            int top = 0;
            for (int i = 0; i < heads.size(); i++) {
                int count = 0;
                for (int k = 0; k < heads.size(); k++) if (heads.get(k).equals(heads.get(i))) count++;
                if (count > top) top = count;
            }
            out[8] = (heads.size() - top) / (double) heads.size();
        }
        if (lengths.length >= 4 && meanLength > 0d) {
            double numerator = 0d, denominator = 0d;
            for (int i = 0; i + 1 < lengths.length; i++)
                numerator += (lengths[i] - meanLength) * (lengths[i + 1] - meanLength);
            for (int i = 0; i < lengths.length; i++) denominator += (lengths[i] - meanLength) * (lengths[i] - meanLength);
            out[9] = denominator > 0d ? numerator / denominator : 0d;
        }
        out[10] = parallelDensity(p);
        double valid = p.validChars;
        if (valid > 0d) {
            int cites = 0, digits = 0;
            for (int i = 0; i < p.clean.length(); i++) {
                if (p.clean.charAt(i) == '[') {
                    int k = i + 1;
                    while (k < p.clean.length() && Character.isDigit(p.clean.charAt(k))) k++;
                    if (k > i + 1) {
                        cites++;
                        i = k - 1;
                    }
                }
            }
            for (int i = 0; i < p.clean.length(); i++) if (Character.isDigit(p.clean.charAt(i))) digits++;
            out[11] = cites * 1000d / valid;
            out[12] = digits * 100d / valid;
        }
        if (p.sentences.size() > 0) {
            int on = 0;
            for (int i = 0; i < p.sentences.size(); i++) if (startsWithAny(p.sentences.get(i), CONNECTIVES)) on++;
            out[13] = on / (double) p.sentences.size();
        }
        out[14] = p.sentences.size();
    }

    /** R11：三个及以上长度接近、用顿号或逗号相连的片段。 */
    private static double parallelDensity(Profile p) {
        int hit = 0, total = 0;
        char[] seps = {'、', '，'};
        for (int s = 0; s < p.sentences.size(); s++) {
            String sentence = p.sentences.get(s);
            for (int k = 0; k < seps.length; k++) {
                ArrayList<String> parts = split(sentence, seps[k]);
                if (parts.size() < 3) continue;
                total++;
                for (int i = 0; i + 2 < parts.size(); i++) {
                    int a = codeLen(parts.get(i)), b = codeLen(parts.get(i + 1)), c = codeLen(parts.get(i + 2));
                    int lo = Math.min(a, Math.min(b, c)), hi = Math.max(a, Math.max(b, c));
                    if (hi <= 1.3d * Math.max(2, lo)) {
                        hit++;
                        break;
                    }
                }
            }
        }
        return total == 0 ? 0d : hit / (double) total;
    }

    /* ---- 下面这些工具函数是 tools/aigc-rhythm.py 里同名口径的逐条直译 ---- */

    /** Python {@code re.sub(r"\s+","",t)}：去掉全部空白。 */
    private static String clean(CharSequence text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            int width = Character.charCount(cp);
            if (!isBlank(cp)) out.appendCodePoint(cp);
            i += width;
        }
        return out.toString();
    }

    private static boolean isBlank(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    /** 分句：只用 {@code 。！？；?!;}，句号留在句尾；去掉空白后不足 2 个字符的不算一句。 */
    private static ArrayList<String> sents(String text) {
        ArrayList<String> kept = new ArrayList<String>();
        if (text == null) return kept;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            int width = Character.charCount(cp);
            if (indexOf(SENT_END, cp) >= 0) {
                String cleaned = clean(current);
                if (cleaned.length() > 0) kept.add(cleaned);
                current.setLength(0);
            } else {
                current.appendCodePoint(cp);
            }
            i += width;
        }
        String tail = clean(current);
        if (tail.length() > 0) kept.add(tail);
        for (int i = kept.size() - 1; i >= 0; i--) if (codeLen(kept.get(i)) < 2) kept.remove(i);
        return kept;
    }

    /** 按一个分隔符切开，逐段去空白，丢掉空段（Python 的 {@code split} + 过滤）。 */
    private static ArrayList<String> split(String sentence, char separator) {
        ArrayList<String> out = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < sentence.length(); i++) {
            char c = sentence.charAt(i);
            if (c == separator) {
                String cleaned = clean(current);
                if (cleaned.length() > 0) out.add(cleaned);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String tail = clean(current);
        if (tail.length() > 0) out.add(tail);
        return out;
    }

    private static int[] clauseLengths(String sentence) {
        ArrayList<Integer> lengths = new ArrayList<Integer>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < sentence.length(); i++) {
            char c = sentence.charAt(i);
            if (indexOf(CLAUSE_SEP, c) >= 0) {
                if (current.length() > 0) lengths.add(Integer.valueOf(codeLen(current)));
                current.setLength(0);
            } else if (!isBlank(c)) {
                current.append(c);
            }
        }
        if (current.length() > 0) lengths.add(Integer.valueOf(codeLen(current)));
        int[] out = new int[lengths.size()];
        for (int i = 0; i < out.length; i++) out[i] = lengths.get(i).intValue();
        return out;
    }

    private static int commas(String sentence) {
        int out = 0;
        for (int i = 0; i < sentence.length(); i++) {
            char c = sentence.charAt(i);
            if (c == '，' || c == '、') out++;
        }
        return out;
    }

    /** 密度判据的分母：中日韩汉字 + 英文字母 + 数字的个数。 */
    private static int validLen(String text) {
        int out = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) out++;
        }
        return out;
    }

    private static String head2(String sentence) {
        int limit = Math.min(sentence.codePointCount(0, sentence.length()), 2);
        int end = sentence.offsetByCodePoints(0, limit);
        return sentence.substring(0, end);
    }

    private static boolean startsWithAny(String sentence, String[] heads) {
        for (int i = 0; i < heads.length; i++) if (sentence.startsWith(heads[i])) return true;
        return false;
    }

    private static int indexOf(String set, int codePoint) {
        for (int i = 0; i < set.length(); ) {
            int cp = set.codePointAt(i);
            if (cp == codePoint) return i;
            i += Character.charCount(cp);
        }
        return -1;
    }

    private static int codeLen(CharSequence text) {
        return text == null ? 0 : Character.codePointCount(text, 0, text.length());
    }

    private static double mean(int[] values) {
        if (values.length == 0) return 0d;
        double total = 0d;
        for (int i = 0; i < values.length; i++) total += values[i];
        return total / values.length;
    }

    private static double mean(double[] values) {
        if (values.length == 0) return 0d;
        double total = 0d;
        for (int i = 0; i < values.length; i++) total += values[i];
        return total / values.length;
    }

    /** 总体标准差（除以 n），不足两个样本给 0，与 Python 侧 stdev 一样。 */
    private static double stdev(int[] values) {
        if (values.length < 2) return 0d;
        double average = mean(values), total = 0d;
        for (int i = 0; i < values.length; i++) total += (values[i] - average) * (values[i] - average);
        return Math.sqrt(total / values.length);
    }

    private static double stdev(double[] values) {
        if (values.length < 2) return 0d;
        double average = mean(values), total = 0d;
        for (int i = 0; i < values.length; i++) total += (values[i] - average) * (values[i] - average);
        return Math.sqrt(total / values.length);
    }
}

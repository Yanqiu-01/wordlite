package com.rikkahub.wordlite;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 降重闭环（{@link AigcRewrite}）实测审计。只读产品代码，一个字都不改，也不写进 app/。
 *
 * <p>要回答的三件事：① 降重把可疑段改完之后，同一件仪器（{@link AigcRhythm}）再量，段分真降了多少、
 * 有多少段真的从可疑变成不可疑；② 真稿语料（真人学位论文正文 / 已发表论文摘要逐字）被改坏了多少；
 * ③ 分数下降到底来自"真改写"还是"钻节奏判据的空子"——所以除了跑产品路径，还单独跑一组
 * **一个词都不换**的机械手术（只拆长句 / 只并短句 / 只插文献编号），看分数能被这些动作压下去多少。
 *
 * <p>口径纪律：
 * ① 产品路径原样调用 {@code AigcRewrite.run(text, new int[0], new ArrayList<String>())}，
 *    也就是 {@link com.rikkahub.wordlite.EditorActivity} 那条"降重并复测"用的默认预算
 *    （depth 6 / candidates 3 / rounds 3 / regions 12 / verifications 24）。
 * ② 段级改前改后的分不复用 {@code Region} 账上的数，本审计自己对同一段调
 *    {@link AigcRhythm#measure(String, int, int)} 复算一遍，两个数并排列出来，对不上就是缺陷。
 * ③ excluded 传空数组：引用与参考文献区间不豁免，这样"数字与文献标注会不会丢"才真的被压到。
 *
 * <p>跑法（仓库根目录）：
 * <pre>
 *   javac -encoding UTF-8 -nowarn -d artifacts/build/agent-rewrite \
 *         -cp tools/android-35.jar -sourcepath "app/src/main/java;tests" \
 *         tests/AigcRewriteAuditRegression.java
 *   java -Dfile.encoding=UTF-8 -cp "tools/android-35.jar;artifacts/build/agent-rewrite" \
 *        com.rikkahub.wordlite.AigcRewriteAuditRegression --docs 16
 * </pre>
 */
public final class AigcRewriteAuditRegression {

    /* ============================ 语料与规模 ============================ */

    static final String RHYTHM_TSV = "app/src/main/assets/aigc/aigc-rhythm.tsv";
    static final String TELLS_TSV = "app/src/main/assets/aigc/ai-tells.tsv";
    static final String DRLX = "E:/download/claude/aigc-corpus/drlx_zh_general.jsonl";
    static final String OUT_DIR = "artifacts/build/agent-rewrite";
    /** 装载哪张判据表：默认就是仓库资产；--asset 可以换成旧的 15 条快照。 */
    static String asset = RHYTHM_TSV;

    /** 一篇文档拼几个自然段：整篇有效字不足 400（AigcDetector.MIN_DOCUMENT_CHARS）量不出指数。 */
    static final int DOC_PARAS = 3;
    /** 段落门槛就取仪器自己的入门条件（120 有效字 / 4 句）：够不到的段永远判不出可疑，不参与。 */
    static final int MIN_PARA_CHARS = AigcRhythm.MIN_FLAG_CHARS;
    static final int MIN_PARA_SENTS = AigcRhythm.MIN_FLAG_SENTENCES;

    static int machineDocs = 24, humanDocs = 24, mixedDocs = 10, forcedParas = 24, probeParas = 60;

    static final String SENT_END = "。！？；?!;";
    /** 断句类标点：这些计数掉了就是"标点丢了 / 断句被破坏"。 */
    static final String COUNT_PUNCT = "。！？；，、：";
    static final Pattern CITE = Pattern.compile(
            "[\\[［][0-9０-９]+(?:\\s*[-–—,，、;；]\\s*[0-9０-９]+)*[\\]］]");

    /* ============================ 记录结构 ============================ */

    static final class Para {
        String pool = "", text = "";
        Para(String pool, String text) { this.pool = pool; this.text = text; }
    }

    /** 一篇文档跑完的整篇账：产品自己报的数 + 本审计复算的数 + 落笔回放。 */
    static final class DocRecord {
        String kind = "", verdict = "", reason = "", error = "";
        int index, suspectParas;
        boolean single;
        boolean measured, rolledBack, textChanged, applyFidelity = true;
        int flaggedBefore, flaggedAfter, charsBefore, charsAfter, regions, adopted, verifications;
        /** measured=true 但一个可疑段都没有：这时产品的 indexAfter / comparedCharsAfter 从没被赋值。 */
        boolean emptyRegions;
        double indexBefore, indexAfter;
        long millis;
        /** 按 UI 那套写法落笔后的正文，与复测时量到的那份文本的字数差：正 = 多留了原文残渣，负 = 吞掉了正文。 */
        int applyDelta;
        /** 没被判可疑的段落里，有多少段在改完之后不再一字不变（<=0 表示没问题；-1 = 连行数都变了）。 */
        int collateral = 0;
        ArrayList<Integer> regionRows = new ArrayList<Integer>();
    }

    /** 一个被判定可疑（或被强制下笔）的段落的完整账。 */
    static final class Row {
        String kind = "", pool = "", reason = "";
        int doc, line;
        boolean single;
        boolean changed, attempted, noOptions, reasonConflict;
        double before, after;                 // 本审计独立复算的段分
        double appBefore, appAfter;           // 产品 Region 账上的段分
        boolean suspBefore, suspAfter, appFlaggedAfter;
        int charsBefore, charsAfter, rawBefore, rawAfter;
        int sentsBefore, sentsAfter;
        int digitsBefore, digitsAfter, citeBefore, citeAfter;
        int punctBefore, punctAfter;
        int dupBefore, dupAfter;
        int removed, added, blocks, bigDeletions;
        /** 产品说这段改了，但按它自己给的偏移去改后正文里切，切出来的不是它记账的那份——切片串位。 */
        int sliceMismatch;
        double[] contribBefore = new double[AigcRhythm.CRITERIA];
        double[] contribAfter = new double[AigcRhythm.CRITERIA];
        ArrayList<String> blockList = new ArrayList<String>();
        String original = "", adoptedText = "";
    }

    static final ArrayList<DocRecord> DOCS = new ArrayList<DocRecord>();
    static final ArrayList<Row> ROWS = new ArrayList<Row>();
    /** 全局改动字面频次：adopted 段里每一处 "原→新"。 */
    static final Map<String, Integer> TOUCHES = new TreeMap<String, Integer>();

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--docs".equals(args[i])) machineDocs = humanDocs = Integer.parseInt(args[i + 1]);
            else if ("--mixed".equals(args[i])) mixedDocs = Integer.parseInt(args[i + 1]);
            else if ("--forced".equals(args[i])) forcedParas = Integer.parseInt(args[i + 1]);
            else if ("--probe".equals(args[i])) probeParas = Integer.parseInt(args[i + 1]);
            else if ("--asset".equals(args[i])) asset = args[i + 1];
        }
        install();
        Corpus corpus = loadCorpus();
        header(corpus);

        experimentMachine(corpus);
        experimentHuman(corpus);
        experimentMixed(corpus);
        offsetProbe(corpus);
        experimentForced(corpus);
        probes(corpus);

        tables();
        samples();
        writeTsv();
        defects();
    }

    /* ============================ 仪器 ============================ */

    /** 仓库资产当前的格式 Java 侧读不出来时，审计用的表落在这里（不在 app/ 下）。 */
    static final String SNAPSHOT = OUT_DIR + "/audit-rhythm.tsv";
    static String instrumentNote = "";
    /** 无可疑段那组里产品自己写出来的那句结论，把改后读数报成 0 这件事原话贴出来。 */
    static String emptyVerdictSample = "";
    /** 第一处「UI 落笔的那份正文」与「复测量过的那份正文」对不上的地方，原话贴出来当证据。 */
    static String[] fidelitySample = new String[0];

    /** 两份文本的第一处分歧：各贴一段上下文。 */
    static String[] driftText(String original, String written, String measured) {
        int p = 0;
        while (p < written.length() && p < measured.length() && written.charAt(p) == measured.charAt(p)) p++;
        int from = Math.max(0, p - 30);
        return new String[] {
                "分歧从第 " + p + " 字开始（原文 " + original.length() + " 字，UI 落笔 " + written.length()
                        + " 字，复测 " + measured.length() + " 字）",
                "UI 真正写进正文的：" + tail(written, from),
                "复测时被量到的正文：" + tail(measured, from)
        };
    }

    static String tail(String text, int from) {
        if (from >= text.length()) return "（这份到这里就结束了，另一份还有字）";
        int to = Math.min(text.length(), from + 110);
        return (from > 0 ? "…" : "") + text.substring(from, to) + (to < text.length() ? "…" : "（结尾）");
    }

    /**
     * 装载判据表。
     *
     * <p>仓库资产 {@code aigc-rhythm.tsv} 目前是 19 行新表，四个分位数写在同一行注释里
     * （{@code # human_p50=... human_p90=... human_p99=... human_max=...}），而 {@link AigcRhythm}
     * 的注释解析每行只认第一个 {@code key=value}，于是三个分位全成 NaN、{@code engaged()} 为 false，
     * 真机上这件仪器是不干活的。审计要的段分只可能由 R01~R15 给出（L02~L05 的 feature=-1，
     * Java 侧不参与求和），配套的分位就是这批 R 权重定出来的 0.2762 / 1.566 / 2.3777。
     * 所以退一步：把资产原样抄一份到 artifacts 下，按解析器认的格式一行一个 key 把分位补齐再装载，
     * 并把这件事写在报告最前面。R01~R15 的权重与仓库资产逐条一致，一个都没动。
     */
    static void install() throws IOException {
        AigcRhythm.install(new FileInputStream(asset));
        if (AigcRhythm.engaged()) {
            instrumentNote = "装载 " + asset + " 成功：matched " + AigcRhythm.criteria().size()
                    + " 条判据对上实现，可疑线取表里的 human_p99=" + f(AigcRhythm.humanP99(), 4);
        } else {
            snapshot();
            AigcRhythm.install(new FileInputStream(SNAPSHOT));
            instrumentNote = "仓库资产 " + RHYTHM_TSV + " 装载后 engaged()=false（L 行不计入 matched + 分位写在同一行解析不出）。"
                    + "审计改用 " + SNAPSHOT + "：R01~R15 权重原样 + 补 " + dummyCount
                    + " 条 weight=0 的占位行把 matched 顶到 CRITERIA=" + AigcRhythm.CRITERIA
                    + "（权重 0 不进分数，段分仍等于 R01~R15 加权和）+ 按任务口径补 "
                    + "human_p50=0.2762 / p90=1.566 / p99=2.3777（分位必须一行一个 key）";
        }
        AigcTells.install(new FileInputStream(TELLS_TSV));
        if (!AigcRhythm.engaged()) throw new IllegalStateException("节奏判据表没装载成功: " + RHYTHM_TSV);
    }

    /** 原样抄资产，末尾按解析器的格式补上分位数。 */
    static void snapshot() throws IOException {
        new File(OUT_DIR).mkdirs();
        BufferedReader read = new BufferedReader(
                new InputStreamReader(new FileInputStream(RHYTHM_TSV), "UTF-8"));
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(new File(SNAPSHOT)), "UTF-8"));
        String line;
        while ((line = read.readLine()) != null) { w.write(line); w.write("\n"); }
        read.close();
        // engaged() 要求 matched >= AigcRhythm.CRITERIA，而 featureIndex 只认 R 开头的 id：
        // 资产里 L02~L05 那四条 feature=-1，matched 永远到不了 19，仪器在任何一张表上都不干活。
        // 这里补几条 weight=0 的占位行把 matched 顶上去——权重 0 的分量恒等于 0，
        // 段分仍然恰好等于 R01~R15 的加权和，一个数的口径都没动。
        int dummies = 0;
        for (int k = 16; k <= AigcRhythm.CRITERIA; k++) {
            w.write("R" + k + "\t占位-本机未实现的第" + k + "条\t0\t1\t1\t0\t0\t0\t0\t0\n");
            dummies++;
        }
        dummyCount = dummies;
        w.write("# human_p50=0.2762\n");
        w.write("# human_p90=1.566\n");
        w.write("# human_p99=2.3777\n");
        w.write("# human_max=4.2582\n");
        w.write("# machine_p50=2.0385\n");
        w.write("# 补齐说明=分位数按 R01~R15 那一版定标写死；R 权重与仓库资产逐条一致\n");
        w.close();
    }

    static int dummyCount;

    static void header(Corpus c) {
        System.out.println("# AigcRewrite 实测审计 —— 仪器与语料");
        System.out.println("# 仪器装载：" + instrumentNote);
        System.out.println("判据表 " + AigcRhythm.version() + " · 条数 " + AigcRhythm.criteria().size()
                + " · 真人段分 P50/P90/P99 = " + f(AigcRhythm.humanP50(), 4) + "/"
                + f(AigcRhythm.humanP90(), 4) + "/" + f(AigcRhythm.humanP99(), 4)
                + " · 机器段中位 " + f(AigcRhythm.machineP50(), 4));
        System.out.println("可疑线：段分 > " + f(AigcRhythm.humanP99(), 4) + " 且 >= "
                + AigcRhythm.MIN_FLAG_CHARS + " 有效字 且 >= " + AigcRhythm.MIN_FLAG_SENTENCES + " 句");
        System.out.println("产品预算：depth " + new AigcRewrite.Limits().depth
                + " / candidates " + new AigcRewrite.Limits().candidates
                + " / rounds " + new AigcRewrite.Limits().rounds
                + " / regions " + new AigcRewrite.Limits().regions
                + " / verifications " + new AigcRewrite.Limits().verifications);
        System.out.println("语料：M-RAW " + c.count("M-RAW") + " 段 · M-EVADE " + c.count("M-EVADE")
                + " · M-DOMAIN " + c.count("M-DOMAIN") + " · M-DRLX " + c.count("M-DRLX")
                + " · H-正文 " + c.count("H-THESIS") + " · H-摘要 " + c.count("H-ABS")
                + " · H-DRLX " + c.count("H-DRLX") + " · X-润色 " + c.count("X-POLISH")
                + " · 配对行 " + c.pairs.size());
        quantileCheck(c);
        System.out.println();
    }

    /** 自检：这批真人段落在当前系数下的分数分布，分位对不对得上表里写的那三个数。 */
    static void quantileCheck(Corpus c) {
        ArrayList<Double> human = new ArrayList<Double>(), machine = new ArrayList<Double>();
        for (int i = 0; i < c.human.size(); i++) human.add(Double.valueOf(score(c.human.get(i).text)));
        for (int i = 0; i < c.machine.size(); i++) machine.add(Double.valueOf(score(c.machine.get(i).text)));
        System.out.println("# 真人段实分（本审计语料 " + human.size() + " 段）：中位 " + f(q(human, 0.5), 4)
                + " · P90 " + f(q(human, 0.9), 4) + " · P99 " + f(q(human, 0.99), 4)
                + " · 过线（>=120字/4句口径）" + falsePositive(c.human) + " 段");
        System.out.println("# 机器段实分（本审计语料 " + machine.size() + " 段）：中位 "
                + f(q(machine, 0.5), 4) + " · 被判可疑 " + flagged(c.machine) + " 段");
    }

    static double score(String text) {
        return AigcRhythm.measure(text, 0, text.length()).score;
    }

    static int falsePositive(ArrayList<Para> list) {
        int out = 0;
        for (int i = 0; i < list.size(); i++)
            if (AigcRhythm.measure(list.get(i).text, 0, list.get(i).text.length()).suspicious) out++;
        return out;
    }

    static int flagged(ArrayList<Para> list) {
        int out = 0;
        for (int i = 0; i < list.size(); i++)
            if (AigcRhythm.measure(list.get(i).text, 0, list.get(i).text.length()).suspicious) out++;
        return out;
    }

    static double q(ArrayList<Double> values, double p) {
        if (values.isEmpty()) return Double.NaN;
        ArrayList<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted);
        int at = (int) Math.round(p * (sorted.size() - 1));
        return sorted.get(at).doubleValue();
    }

    /* ============================ 语料装载 ============================ */

    static final class Corpus {
        final ArrayList<Para> machine = new ArrayList<Para>();
        final ArrayList<Para> human = new ArrayList<Para>();
        /** DetectRL-X 同一行的配对：真人原文 + 同选题机器稿。 */
        final ArrayList<Para[]> pairs = new ArrayList<Para[]>();

        int count(String pool) {
            int n = 0;
            for (int i = 0; i < machine.size(); i++) if (machine.get(i).pool.equals(pool)) n++;
            for (int i = 0; i < human.size(); i++) if (human.get(i).pool.equals(pool)) n++;
            return n;
        }
    }

    static boolean usable(String text) {
        return validLen(text) >= MIN_PARA_CHARS && sents(text).size() >= MIN_PARA_SENTS;
    }

    static Corpus loadCorpus() throws IOException {
        Corpus c = new Corpus();
        readLabel(c.machine, "M-RAW", "tests/corpus/aigc-label-machine-raw.txt");
        readLabel(c.machine, "M-EVADE", "tests/corpus/aigc-label-machine-evasive.txt");
        readLabel(c.machine, "M-DOMAIN", "tests/corpus/aigc-label-machine-domain.txt");
        readLabel(c.human, "H-ABS", "tests/corpus/aigc-label-human-verbatim.txt");
        readLabel(c.human, "H-THESIS", "tests/corpus/real-prose.txt");
        readLabel(c.human, "X-POLISH", "tests/corpus/aigc-label-human-polish-agent.txt");
        if (new File(DRLX).isFile()) loadDrlx(c);
        else System.out.println("!! 找不到 " + DRLX + "，DRLX 两池为空");
        return c;
    }

    /** 标注语料：一行一段，# 开头是说明。 */
    static void readLabel(ArrayList<Para> into, String pool, String path) throws IOException {
        BufferedReader read = new BufferedReader(
                new InputStreamReader(new FileInputStream(path), "UTF-8"));
        String line;
        while ((line = read.readLine()) != null) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) continue;
            if (usable(text)) into.add(new Para(pool, text));
        }
        read.close();
    }

    /** DetectRL-X 中文域 Academic 档：真人原文与同选题机器稿在同一行里配对。 */
    static void loadDrlx(Corpus c) throws IOException {
        int need = Math.max(machineDocs, humanDocs) * DOC_PARAS + 6;
        // 机器侧多取一些给"纯机械手术"那组用，真人侧多取一些给分位自检用。
        int machineCap = Math.max(need, probeParas + 60);
        int humanCap = Math.max(need, 700);
        int needPairs = mixedDocs + 2;
        BufferedReader read = new BufferedReader(
                new InputStreamReader(new FileInputStream(DRLX), "UTF-8"));
        String line;
        int scanned = 0;
        while ((line = read.readLine()) != null
                && (count(c.machine, "M-DRLX") < machineCap || count(c.human, "H-DRLX") < humanCap
                || c.pairs.size() < needPairs)) {
            scanned++;
            Map<String, String> row = jsonFlat(line);
            if (row == null || !"Academic".equals(row.get("domain"))) continue;
            String h = row.get("human_written_text"), m = row.get("llm_generated_text");
            if (h == null || m == null) continue;
            h = h.trim(); m = m.trim();
            if (usable(m) && count(c.machine, "M-DRLX") < machineCap) c.machine.add(new Para("M-DRLX", m));
            if (usable(h) && count(c.human, "H-DRLX") < humanCap) c.human.add(new Para("H-DRLX", h));
            if (usable(m) && usable(h) && c.pairs.size() < needPairs) {
                c.pairs.add(new Para[]{new Para("H-DRLX", h), new Para("M-DRLX", m)});
            }
        }
        read.close();
        System.out.println("# DRLX 扫描 " + scanned + " 行，取 Academic 机器段 "
                + count(c.machine, "M-DRLX") + " / 真人段 " + count(c.human, "H-DRLX")
                + " / 配对 " + c.pairs.size());
    }

    static int count(ArrayList<Para> list, String pool) {
        int n = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).pool.equals(pool)) n++;
        return n;
    }

    /** 只要顶层 {"key":"value",...}：够读 human_written_text / llm_generated_text / domain 就够了。 */
    static Map<String, String> jsonFlat(String line) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        int i = line.indexOf('{');
        if (i < 0) return null;
        i++;
        try {
            while (i < line.length()) {
                while (i < line.length() && (line.charAt(i) == ',' || Character.isWhitespace(line.charAt(i)))) i++;
                if (i >= line.length() || line.charAt(i) == '}') break;
                if (line.charAt(i) != '"') return null;
                String key = jsonString(line, i);
                if (key == null) return null;
                i = keyEnd(line, i);
                while (i < line.length() && line.charAt(i) != ':') i++;
                i++;
                while (i < line.length() && Character.isWhitespace(line.charAt(i))) i++;
                char c = line.charAt(i);
                if (c == '"') {
                    String value = jsonString(line, i);
                    if (value == null) return null;
                    out.put(key, value);
                    i = keyEnd(line, i);
                } else {
                    int j = i;
                    while (j < line.length() && line.charAt(j) != ',' && line.charAt(j) != '}') j++;
                    i = j;
                }
            }
        } catch (RuntimeException bad) {
            return null;
        }
        return out;
    }

    /** 从 pos 处的引号读出一个字符串值（反转义），返回内容；调用方用 keyEnd 找结束位置。 */
    static String jsonString(String line, int pos) {
        StringBuilder out = new StringBuilder();
        int i = pos + 1;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '"') return out.toString();
            if (c != '\\') { out.append(c); i++; continue; }
            char n = line.charAt(i + 1);
            switch (n) {
                case 'n': out.append('\n'); break;
                case 't': out.append('\t'); break;
                case 'r': out.append('\r'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case '/': out.append('/'); break;
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case 'u': out.append((char) Integer.parseInt(line.substring(i + 2, i + 6), 16)); break;
                default: return null;
            }
            i += n == 'u' ? 6 : 2;
        }
        return null;
    }

    static int keyEnd(String line, int quoteStart) {
        int i = quoteStart + 1;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '"') return i + 1;
            i++;
        }
        return line.length();
    }

    /* ============================ 实验 1/2/3：跑产品闭环 ============================ */

    /**
     * 单可疑段文档：一个可疑段 + 两个判不出可疑的段做陪衬（只为凑够整篇 400 字的计分门槛）。
     * 这样一篇里只有一个改动点，先把"降重到底改了什么、分数动了多少"量干净，
     * 再去看多可疑段的稿子上偏移会不会串位。
     */
    static void experimentMachine(Corpus c) {
        ArrayList<Para> pool = weave(c.machine);
        singleDocs("机器段", pool, machineDocs);
        naturalDocs("机器段-多篇", pool, machineDocs);
    }

    static void experimentHuman(Corpus c) {
        ArrayList<Para> pool = weave(c.human);
        singleDocs("真人段", pool, humanDocs);
        naturalDocs("真人段-多篇", pool, humanDocs);
    }

    /** 整篇要过 400 有效字的计分门槛，所以每篇至少凑到这么多才开跑。 */
    static final int DOC_TARGET_CHARS = 600;

    static void singleDocs(String kind, ArrayList<Para> pool, int cap) {
        ArrayList<Para> clean = new ArrayList<Para>();
        for (int i = 0; i < pool.size(); i++)
            if (!susp(pool.get(i)) && validLen(pool.get(i).text) >= 150) clean.add(pool.get(i));
        int docs = 0, cursor = 0, skipped = 0;
        for (int i = 0; i < pool.size() && docs < cap; i++) {
            Para target = pool.get(i);
            if (!susp(target)) continue;
            ArrayList<Para> doc = new ArrayList<Para>();
            doc.add(target);
            int guard = 0;
            while (chars(doc) < DOC_TARGET_CHARS && cursor < clean.size() && guard++ < 8)
                doc.add(clean.get(cursor++));
            if (chars(doc) < AigcDetector.MIN_DOCUMENT_CHARS) { skipped++; continue; }
            runDoc(kind, docs, doc, true);
            docs++;
        }
        if (skipped > 0) System.out.println("#   " + kind + "：陪衬段不够凑满 400 字门槛，跳过 " + skipped + " 段");
    }

    /** 自然成篇：从池子里顺着拿，凑到 600 有效字为止，不挑可疑段的个数——真稿就是这个样子。 */
    static void naturalDocs(String kind, ArrayList<Para> pool, int cap) {
        int docs = 0, i = 0;
        while (docs < cap && i < pool.size()) {
            ArrayList<Para> doc = new ArrayList<Para>();
            while (i < pool.size() && (doc.isEmpty() || chars(doc) < DOC_TARGET_CHARS)) doc.add(pool.get(i++));
            if (chars(doc) < AigcDetector.MIN_DOCUMENT_CHARS) continue;
            runDoc(kind, docs, doc, false);
            docs++;
        }
    }

    static int chars(ArrayList<Para> paras) {
        int out = 0;
        for (int i = 0; i < paras.size(); i++) out += validLen(paras.get(i).text);
        return out;
    }

    /** 段级可疑判定与整篇无关（measure 只看那一段），所以可以先筛再拼。 */
    static boolean susp(Para p) {
        return AigcRhythm.measure(p.text, 0, p.text.length()).suspicious;
    }

    /** 各语料池轮流出一段，保证一篇文档里四个池都轮到（不然前 N 段全落在同一个池上）。 */
    static ArrayList<Para> weave(ArrayList<Para> pool) {
        ArrayList<String> names = new ArrayList<String>();
        for (int i = 0; i < pool.size(); i++) if (!names.contains(pool.get(i).pool)) names.add(pool.get(i).pool);
        ArrayList<Para> out = new ArrayList<Para>();
        for (int rank = 0; ; rank++) {
            boolean added = false;
            for (int k = 0; k < names.size(); k++) {
                int seen = 0;
                for (int i = 0; i < pool.size(); i++) {
                    if (!pool.get(i).pool.equals(names.get(k))) continue;
                    if (seen == rank) { out.add(pool.get(i)); added = true; }
                    seen++;
                }
            }
            if (!added) break;
        }
        return out;
    }

    /** 配对混排：同一行的真人原文 + 同选题机器稿拼成一篇，看机器段被改时真人段有没有被连带改坏。 */
    static void experimentMixed(Corpus c) {
        for (int i = 0; i < c.pairs.size() && i < mixedDocs; i++) {
            ArrayList<Para> paras = new ArrayList<Para>();
            paras.add(c.pairs.get(i)[0]);
            paras.add(c.pairs.get(i)[1]);
            runDoc("配对混排", i, paras, false);
        }
    }

    static DocRecord runDoc(String kind, int index, ArrayList<Para> paras, boolean single) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paras.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(paras.get(i).text);
        }
        final String text = sb.toString();
        AigcDetector.Result first = AigcDetector.detect(text, new int[0]);

        DocRecord doc = new DocRecord();
        doc.kind = kind;
        doc.index = index;
        doc.single = single;
        doc.suspectParas = first.suspiciousParagraphs.size();
        doc.measured = AigcDetector.indexable(first);
        doc.flaggedBefore = first.flaggedChars;
        doc.charsBefore = first.comparedChars;
        doc.indexBefore = first.rate;

        long t0 = System.nanoTime();
        AigcRewrite.Result res;
        try {
            res = AigcRewrite.run(text, new int[0], TERMS);
        } catch (Throwable crash) {
            doc.millis = (System.nanoTime() - t0) / 1000000L;
            doc.error = crash.getClass().getSimpleName() + ": " + crash.getMessage();
            doc.verdict = "跑挂了";
            DOCS.add(doc);
            System.out.println(String.format(Locale.US,
                    "# %-10s doc%02d 可疑段%2d 计分%5d字 -> 抛异常 %s",
                    kind, Integer.valueOf(index), Integer.valueOf(doc.suspectParas),
                    Integer.valueOf(doc.charsBefore), doc.error));
            return doc;
        }
        doc.millis = (System.nanoTime() - t0) / 1000000L;

        doc.verdict = res.verdict();
        doc.measured = doc.measured && res.measured;
        doc.indexAfter = res.indexAfter;
        doc.flaggedAfter = res.flaggedAfter;
        doc.charsAfter = res.comparedCharsAfter;
        doc.regions = res.regions;
        doc.adopted = res.adoptedRegions;
        doc.verifications = res.verifications;
        doc.emptyRegions = res.measured && res.regions == 0;
        if (doc.emptyRegions && emptyVerdictSample.length() == 0 && res.verdict().length() > 0)
            emptyVerdictSample = res.verdict();
        doc.textChanged = !res.text.equals(text);
        doc.rolledBack = !doc.textChanged && doc.regions > 0 && doc.adopted == 0;

        ArrayList<int[]> spans = new ArrayList<int[]>();
        for (int i = 0; i < res.accounts.size(); i++) {
            AigcRewrite.Region g = res.accounts.get(i);
            spans.add(new int[]{g.originStart, g.originEnd});
            ROWS.add(account(doc, text, res, g, paras));
        }
        doc.collateral = collateral(text, res.text, spans);
        String replay = rebuild(text, res);
        doc.applyFidelity = replay.equals(res.text);
        if (!doc.applyFidelity && fidelitySample.length == 0)
            fidelitySample = driftText(text, replay, res.text);
        /** 按 EditorActivity 的写法落笔之后，正文与复测那份文本差多少个字。 */
        doc.applyDelta = replay.length() - res.text.length();
        DOCS.add(doc);
        System.out.println(String.format(Locale.US,
                "# %-10s doc%02d 计分%5d字 可疑%4d字 可疑段%2d 采纳%2d 复测%2d次 %5dms 误伤%d 落笔%s",
                kind, Integer.valueOf(index), Integer.valueOf(doc.charsBefore),
                Integer.valueOf(doc.flaggedBefore), Integer.valueOf(doc.regions),
                Integer.valueOf(doc.adopted), Integer.valueOf(doc.verifications),
                Long.valueOf(doc.millis), Integer.valueOf(doc.collateral),
                doc.applyFidelity ? "一致" : "不一致"));
        return doc;
    }

    /**
     * 实验 6：偏移串位定点探针。
     *
     * <p>{@link AigcRewrite#run} 里 {@code regions(...)} 把可疑段按**段分从高到低**排，
     * 采纳循环却按"从列表尾往头走"当作"从正文尾往头改"（AigcRewrite.java:141 的注释就是这么写的）。
     * 两个次序不是一回事：只要先采纳的那一段改了长度，后面还没处理的段用的就还是原文偏移。
     * 这一组专挑"前面的段分低、后面的段分高"的可疑段对拼成一篇，把这条路径钉死。
     */
    static void offsetProbe(Corpus c) {
        System.out.println();
        System.out.println("# 实验 6：偏移串位探针（前面可疑段分低 + 后面可疑段分高，凑成一篇）");
        ArrayList<Para> flagged = new ArrayList<Para>();
        ArrayList<Para> pool = weave(c.machine);
        for (int i = 0; i < pool.size(); i++) if (susp(pool.get(i))) flagged.add(pool.get(i));
        int tried = 0, crashes = 0, shifted = 0;
        boolean[] used = new boolean[flagged.size()];
        for (int i = 0; i < flagged.size() && tried < 10; i++) {
            if (used[i]) continue;
            double si = score(flagged.get(i).text);
            for (int j = i + 1; j < flagged.size(); j++) {
                if (used[j]) continue;
                if (score(flagged.get(j).text) <= si) continue;
                ArrayList<Para> doc = new ArrayList<Para>();
                doc.add(flagged.get(i));
                doc.add(flagged.get(j));
                DocRecord d = runDoc("偏移探针", tried, doc, false);
                tried++;
                used[i] = true;
                used[j] = true;
                if (d.error.length() > 0) crashes++;
                else if (rowsOf(d).sliceMismatch > 0 || rowsOf(d).bigDeletions > 0) shifted++;
                break;
            }
        }
        System.out.println("#   试了 " + tried + " 对：抛异常 " + crashes + " 对，切片串位（记账与正文对不上）"
                + shifted + " 对");
        probeCrashes = crashes;
        probeShifted = shifted;
        probeTried = tried;
    }

    static int probeTried, probeCrashes, probeShifted;

    /** 这一篇产出的段级账合起来看（探针只关心有没有出现大块删除）。 */
    static Row rowsOf(DocRecord doc) {
        Row merged = new Row();
        int from = 0;
        for (int i = 0; i < DOCS.size(); i++) if (DOCS.get(i) == doc) from = i;
        // ROWS 是按 doc 顺序追加的，这里按 kind+index 找回来
        for (int i = 0; i < ROWS.size(); i++) {
            Row r = ROWS.get(i);
            if (!r.kind.equals(doc.kind) || r.doc != doc.index) continue;
            merged.removed += r.removed;
            merged.added += r.added;
            merged.bigDeletions += r.bigDeletions;
            merged.sliceMismatch += r.sliceMismatch;
        }
        return merged;
    }

    static final List<String> TERMS = new ArrayList<String>();
    static final AigcRewrite.Limits LIMITS = new AigcRewrite.Limits();

    static Row account(DocRecord doc, String original, AigcRewrite.Result res,
                       AigcRewrite.Region g, ArrayList<Para> paras) {
        Row r = new Row();
        r.kind = doc.kind;
        r.doc = doc.index;
        r.single = doc.single;
        r.changed = g.changed;
        r.reasonConflict = g.changed && g.reason != null && g.reason.length() > 0;
        r.appBefore = g.indexBefore;
        r.appAfter = g.indexAfter;
        r.appFlaggedAfter = g.flaggedAfter > 0;
        r.reason = g.reason;
        int os = clamp(g.originStart, original.length()), oe = clamp(g.originEnd, original.length());
        r.original = original.substring(Math.min(os, oe), Math.max(os, oe));
        String after = res.text;
        int s = clamp(g.start, after.length()), e = clamp(g.end, after.length());
        r.adoptedText = after.substring(Math.min(s, e), Math.max(s, e));
        // 产品记账说这段换成了 adopted；按它给的 start/end 去改后正文里切，切出来必须就是那一份。
        // 对不上就是偏移串位：正文里落的是另一处字，报告与屏幕各说一套。
        r.sliceMismatch = g.changed && !r.adoptedText.equals(g.adopted) ? 1 : 0;
        r.line = lineOf(original, os);
        r.pool = r.line >= 0 && r.line < paras.size() ? paras.get(r.line).pool : "?";

        AigcRhythm.Reading rb = AigcRhythm.measure(original, os, oe);
        AigcRhythm.Reading ra = AigcRhythm.measure(after, s, e);
        r.before = rb.score;
        r.after = ra.score;
        r.suspBefore = rb.suspicious;
        r.suspAfter = ra.suspicious;
        r.charsBefore = rb.validChars;
        r.charsAfter = ra.validChars;
        r.sentsBefore = rb.sentences;
        r.sentsAfter = ra.sentences;
        r.contribBefore = contributions(rb);
        r.contribAfter = contributions(ra);
        r.rawBefore = r.original.length();
        r.rawAfter = r.adoptedText.length();
        r.digitsBefore = digits(r.original);
        r.digitsAfter = digits(r.adoptedText);
        r.citeBefore = cites(r.original);
        r.citeAfter = cites(r.adoptedText);
        r.punctBefore = punct(r.original);
        r.punctAfter = punct(r.adoptedText);
        r.dupBefore = dupSentences(r.original);
        r.dupAfter = dupSentences(r.adoptedText);
        r.attempted = g.reason.length() > 0 || g.changed;
        r.noOptions = optionsFor(r.original).isEmpty();
        diffRegion(r);
        // 探针那组的切片本来就是错的，不计进"改了哪几个字"的频次表
        if (r.changed && !r.kind.equals("偏移探针")) for (int i = 0; i < r.blockList.size(); i++) {
            String key = r.blockList.get(i);
            Integer old = TOUCHES.get(key);
            TOUCHES.put(key, Integer.valueOf(old == null ? 1 : old.intValue() + 1));
        }
        return r;
    }

    /** 复现产品那一步的下笔机会：同一条 mask、同一份预算，只问"有没有候选"。 */
    static ArrayList<LocalRewriter.Option> optionsFor(String piece) {
        try {
            DocxDocument.ParagraphBlock block = new DocxDocument.ParagraphBlock();
            block.text = piece;
            if (TextProtection.referenceParagraph(block)) return new ArrayList<LocalRewriter.Option>();
            TextProtection.Mask mask = TextProtection.mask(block, 0, piece.length(), TERMS);
            return LocalRewriter.rewrite(mask.submitted, TERMS, LIMITS.depth, LIMITS.candidates);
        } catch (RuntimeException e) {
            return new ArrayList<LocalRewriter.Option>();
        }
    }

    /** 复刻 EditorActivity.applyAigcRewrite 的落笔：从最后一段往前把 adopted 写回原文。 */
    static String rebuild(String original, AigcRewrite.Result res) {
        StringBuilder sb = new StringBuilder(original);
        for (int i = res.accounts.size() - 1; i >= 0; i--) {
            AigcRewrite.Region g = res.accounts.get(i);
            if (!g.changed) continue;
            // 与 EditorActivity.applyAigcRewrite 同一条：往**原文**里落笔用的是原文坐标，
            // region.start/end 是改完那篇里的位置，拿它写原文会整片串位。
            int s = clamp(g.originStart, sb.length()), e = clamp(g.originEnd, sb.length());
            if (e < s) continue;
            sb.replace(s, e, g.adopted == null ? "" : g.adopted);
        }
        return sb.toString();
    }

    /** 没进可疑区间的那些自然段，改完之后必须一字不变。 */
    static int collateral(String before, String after, ArrayList<int[]> regions) {
        String[] lb = before.split("\n", -1);
        String[] la = after.split("\n", -1);
        if (lb.length != la.length) return -1;
        int off = 0, bad = 0;
        for (int i = 0; i < lb.length; i++) {
            int from = off, to = off + lb[i].length();
            boolean touched = false;
            for (int k = 0; k < regions.size(); k++)
                if (regions.get(k)[0] < to && regions.get(k)[1] > from) touched = true;
            if (!touched && !lb[i].equals(la[i])) bad++;
            off = to + 1;
        }
        return bad;
    }

    static int lineOf(String text, int offset) {
        int line = 0;
        for (int i = 0; i < offset && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    /* ============================ 实验 4：真人段被迫下笔的上界 ============================ */


    /** 真人段本来不该大改：产品只在段分过线时才动它。这组把闸拿掉，量"若被判可疑会被改成什么样"。 */
    static void experimentForced(Corpus c) {
        System.out.println();
        System.out.println("# 实验 4：绕过可疑闸，强制取第一个能过 flaw() 的候选（同一条 mask、同一份预算）");
        forced(c.human, "H-THESIS", "真人学位论文正文", 120);
        forced(c.human, "H-ABS", "真人已发表论文摘要", 120);
        forced(c.human, "H-DRLX", "DRLX 真人原文", 60);
        forced(c.machine, "M-DRLX", "DRLX 机器稿", 60);
    }

    static void forced(ArrayList<Para> pool, String want, String label, int cap) {
        int n = 0, withOption = 0, changed = 0, lost = 0, punctLost = 0;
        double sumDelta = 0d, sumPct = 0d;
        for (int i = 0; i < pool.size() && n < cap; i++) {
            Para p = pool.get(i);
            if (!p.pool.equals(want)) continue;
            n++;
            String piece = p.text;
            String applied = forcedFirst(piece);
            if (applied == null) continue;
            withOption++;
            if (applied.equals(piece)) continue;
            changed++;
            AigcRhythm.Reading b = AigcRhythm.measure(piece, 0, piece.length());
            AigcRhythm.Reading a = AigcRhythm.measure(applied, 0, applied.length());
            sumDelta += a.score - b.score;
            sumPct += (applied.length() - piece.length()) * 100d / Math.max(1, piece.length());
            if (digits(applied) != digits(piece) || cites(applied) != cites(piece)) lost++;
            if (punct(applied) < punct(piece)) punctLost++;
        }
        System.out.println(String.format(Locale.US,
                "#   %-18s 段%3d 有候选%3d 实际改动%3d 平均段分%+.3f 平均字数%+.2f%% 丢数字或标注%d 丢标点%d",
                label, Integer.valueOf(n), Integer.valueOf(withOption), Integer.valueOf(changed),
                Double.valueOf(changed == 0 ? 0d : sumDelta / changed),
                Double.valueOf(changed == 0 ? 0d : sumPct / changed),
                Integer.valueOf(lost), Integer.valueOf(punctLost)));
    }

    /** 与 AigcRewrite 主循环那一步逐字等价：mask → 候选 → flaw → 取第一个能过的。 */
    static String forcedFirst(String piece) {
        try {
            DocxDocument.ParagraphBlock block = new DocxDocument.ParagraphBlock();
            block.text = piece;
            if (TextProtection.referenceParagraph(block)) return null;
            TextProtection.Mask mask = TextProtection.mask(block, 0, piece.length(), TERMS);
            ArrayList<LocalRewriter.Option> options =
                    LocalRewriter.rewrite(mask.submitted, TERMS, LIMITS.depth, LIMITS.candidates);
            for (int i = 0; i < options.size(); i++) {
                String applied = mask.restore(options.get(i).text);
                if (AigcRewrite.flaw(piece, applied) != null) continue;
                if (applied.equals(piece)) continue;
                return applied;
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /* ============================ 实验 5：一个词都不换的机械手术 ============================ */

    /**
     * 回答"真改写还是钻判据空子"的对照组：不接受任何换词，只做纯排版动作（拆长句 / 并短句 /
     * 插文献编号 / 挂数字），看同一件仪器能被这些动作压下去多少分、能洗白多少可疑段。
     */
    static void probes(Corpus c) {
        System.out.println();
        System.out.println("# 实验 5：纯机械手术（零换词）对被判可疑的机器段的分数影响");
        ArrayList<String> flagged = new ArrayList<String>();
        for (int i = 0; i < c.machine.size() && flagged.size() < probeParas; i++) {
            String t = c.machine.get(i).text;
            if (AigcRhythm.measure(t, 0, t.length()).suspicious) flagged.add(t);
        }
        System.out.println("#   取样：" + flagged.size() + " 个被判可疑的机器段（原文原样，未截断）");
        surgery(flagged, "对照组：原样不动", same());
        surgery(flagged, "只拆长句：逗号改句号", splitLong());
        surgery(flagged, "只并短句：句号改逗号", mergeShort());
        surgery(flagged, "只插文献编号 [n]", addCites());
        surgery(flagged, "只挂测量数字", addNumbers());
    }

    interface Surgery { String apply(String text); }

    static Surgery same() {
        return new Surgery() { public String apply(String t) { return t; } };
    }

    static Surgery splitLong() {
        return new Surgery() {
            public String apply(String t) {
                StringBuilder out = new StringBuilder();
                ArrayList<String> parts = sentsRaw(t);
                for (int i = 0; i < parts.size(); i++) {
                    String s = parts.get(i);
                    if (validLen(stripEnd(s)) >= 40) {
                        int at = s.indexOf('，');
                        if (at > 0 && at + 1 < s.length())
                            s = s.substring(0, at) + "。" + s.substring(at + 1);
                    }
                    out.append(s);
                }
                return out.toString();
            }
        };
    }

    static Surgery mergeShort() {
        return new Surgery() {
            public String apply(String t) {
                StringBuilder out = new StringBuilder();
                ArrayList<String> parts = sentsRaw(t);
                for (int i = 0; i < parts.size(); i++) {
                    String s = parts.get(i);
                    if (out.length() > 0 && validLen(stripEnd(s)) < 24
                            && out.charAt(out.length() - 1) == '。') {
                        out.setLength(out.length() - 1);
                        out.append('，').append(s);
                    } else out.append(s);
                }
                return out.toString();
            }
        };
    }

    static Surgery addCites() {
        return new Surgery() {
            public String apply(String t) {
                StringBuilder out = new StringBuilder();
                ArrayList<String> parts = sentsRaw(t);
                int n = 0;
                for (int i = 0; i < parts.size(); i++) {
                    String s = parts.get(i);
                    if (i % 4 == 3 && s.endsWith("。")) {
                        n++;
                        s = s.substring(0, s.length() - 1) + "[" + n + "]。";
                    }
                    out.append(s);
                }
                return out.toString();
            }
        };
    }

    static Surgery addNumbers() {
        return new Surgery() {
            public String apply(String t) {
                StringBuilder out = new StringBuilder();
                ArrayList<String> parts = sentsRaw(t);
                for (int i = 0; i < parts.size(); i++) {
                    String s = parts.get(i);
                    if (i % 3 == 2 && s.endsWith("。"))
                        s = s.substring(0, s.length() - 1) + "，实测值为 " + (12 + i) + "." + (i % 7) + " mm。";
                    out.append(s);
                }
                return out.toString();
            }
        };
    }

    static void surgery(ArrayList<String> texts, String label, Surgery surgery) {
        int n = 0, still = 0, washed = 0, worse = 0;
        double sumBefore = 0d, sumAfter = 0d, sumPct = 0d;
        for (int i = 0; i < texts.size(); i++) {
            String before = texts.get(i);
            String after = surgery.apply(before);
            AigcRhythm.Reading b = AigcRhythm.measure(before, 0, before.length());
            AigcRhythm.Reading a = AigcRhythm.measure(after, 0, after.length());
            if (!b.suspicious) continue;
            n++;
            sumBefore += b.score;
            sumAfter += a.score;
            sumPct += (a.validChars - b.validChars) * 100d / Math.max(1, b.validChars);
            if (!a.suspicious) washed++; else still++;
            if (a.score > b.score) worse++;
        }
        System.out.println(String.format(Locale.US,
                "#   %-22s 段%3d 平均段分 %.3f -> %.3f (%+.3f) 平均有效字%+.2f%% 仍可疑%3d 被洗白%3d 反升%3d",
                label, Integer.valueOf(n),
                Double.valueOf(n == 0 ? 0d : sumBefore / n),
                Double.valueOf(n == 0 ? 0d : sumAfter / n),
                Double.valueOf(n == 0 ? 0d : (sumAfter - sumBefore) / n),
                Double.valueOf(n == 0 ? 0d : sumPct / n), Integer.valueOf(still),
                Integer.valueOf(washed), Integer.valueOf(worse)));
    }

    /* ============================ 文本量具（与 AigcRhythm 同一口径） ============================ */

    static int clamp(int v, int len) { return v < 0 ? 0 : (v > len ? len : v); }

    static String f(double v, int digits_) { return String.format(Locale.US, "%." + digits_ + "f", Double.valueOf(v)); }

    /** 与 AigcRhythm.validLen 同口径：中日韩汉字 + 字母 + 数字。 */
    static int validLen(String text) {
        int out = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) out++;
        }
        return out;
    }

    /** 与 AigcRhythm.sents 同口径的分句，但保留句末标点，方便原样拼回去。 */
    static ArrayList<String> sentsRaw(String text) {
        ArrayList<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cur.append(c);
            if (SENT_END.indexOf(c) >= 0) { out.add(cur.toString()); cur.setLength(0); }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** 与 AigcRhythm.sents 同口径：去空白、不足 2 字不算一句。 */
    static ArrayList<String> sents(String text) {
        ArrayList<String> out = new ArrayList<String>();
        ArrayList<String> raw = sentsRaw(text);
        for (int i = 0; i < raw.size(); i++) {
            String s = stripEnd(raw.get(i));
            if (s.length() >= 2) out.add(s);
        }
        return out;
    }

    static String stripEnd(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) out.append(c);
        }
        return out.toString();
    }

    static int digits(String text) {
        int out = 0;
        for (int i = 0; i < text.length(); i++) if (Character.isDigit(text.charAt(i))) out++;
        return out;
    }

    /** 文献标注：[1] / [1,2] / ［3］ 一类的个数，外加方括号字符总数（更严的一把尺）。 */
    static int cites(String text) {
        Matcher m = CITE.matcher(text);
        int out = 0;
        while (m.find()) out++;
        return out;
    }

    static int brackets(String text) {
        int out = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("[]\uFF3B\uFF3D".indexOf(c) >= 0) out++;
        }
        return out;
    }

    static int punct(String text) {
        int out = 0;
        for (int i = 0; i < text.length(); i++) if (COUNT_PUNCT.indexOf(text.charAt(i)) >= 0) out++;
        return out;
    }

    /** 段内重复出现的整句个数（改前 0、改后 > 0 就是改写把句子复制了一份）。 */
    static int dupSentences(String text) {
        ArrayList<String> list = sents(text);
        int dup = 0;
        for (int i = 0; i < list.size(); i++) {
            int same = 0;
            for (int k = 0; k < list.size(); k++) if (list.get(k).equals(list.get(i))) same++;
            if (same > 1) dup++;
        }
        return dup;
    }

    /** 每条判据这一段贡献了多少分（与 AigcRhythm 打分同一份系数）。 */
    static double[] contributions(AigcRhythm.Reading reading) {
        double[] out = new double[AigcRhythm.CRITERIA];
        ArrayList<AigcRhythm.Criterion> list = AigcRhythm.criteria();
        for (int i = 0; i < list.size(); i++) {
            AigcRhythm.Criterion c = list.get(i);
            if (c.feature >= 1 && c.feature <= AigcRhythm.CRITERIA)
                out[c.feature - 1] += c.contribution(reading.values[c.feature - 1]);
        }
        return out;
    }

    static String criterionName(int index) {
        ArrayList<AigcRhythm.Criterion> list = AigcRhythm.criteria();
        for (int i = 0; i < list.size(); i++)
            if (list.get(i).feature == index + 1) return list.get(i).id + " " + list.get(i).name;
        return "R" + (index + 1);
    }

    /* ============================ 改动块：它到底改了哪几个字 ============================ */

    static void diffRegion(Row r) {
        ArrayList<String> sa = sentsRaw(r.original), sb = sentsRaw(r.adoptedText);
        ArrayList<String[]> blocks = new ArrayList<String[]>();
        if (sa.size() == sb.size()) {
            for (int i = 0; i < sa.size(); i++)
                if (!sa.get(i).equals(sb.get(i))) blocks.addAll(charBlocks(sa.get(i), sb.get(i)));
        } else {
            blocks.addAll(charBlocks(r.original, r.adoptedText));
        }
        for (int i = 0; i < blocks.size(); i++) {
            String[] b = blocks.get(i);
            // "连续删除"要的是内容真没了：一块里删掉 >=4 字、补回来的又明显更少，才叫删句/截断。
            // 只看删掉那一侧会把"不可或缺→必需"这类四字换词一起数进来（本轮抓到的 5 段全是这种），
            // 数错了方向比数少了更糟。
            r.removed += b[0].length();
            r.added += b[1].length();
            if (b[0].length() >= 4 && b[0].length() - b[1].length() >= 4) r.bigDeletions++;
            r.blocks++;
            if (r.blockList.size() < 40) r.blockList.add(b[0] + "\u2192" + b[1]);
        }
    }

    /** 字符级 LCS 差分，输出"删了什么 / 加了什么"的连续块。 */
    static ArrayList<String[]> charBlocks(String a, String b) {
        ArrayList<String[]> out = new ArrayList<String[]>();
        if (a.equals(b)) return out;
        int n = a.length(), m = b.length();
        int p = 0;
        while (p < n && p < m && a.charAt(p) == b.charAt(p)) p++;
        int s = 0;
        while (s < n - p && s < m - p && a.charAt(n - 1 - s) == b.charAt(m - 1 - s)) s++;
        String midA = a.substring(p, n - s), midB = b.substring(p, m - s);
        if (midA.length() == 0 && midB.length() == 0) return out;
        if (midA.length() > 500 || midB.length() > 500) {
            out.add(new String[]{midA, midB});
            return out;
        }
        int la = midA.length(), lb = midB.length();
        int[][] dp = new int[la + 1][lb + 1];
        for (int i = la - 1; i >= 0; i--)
            for (int j = lb - 1; j >= 0; j--)
                dp[i][j] = midA.charAt(i) == midB.charAt(j)
                        ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
        StringBuilder rem = new StringBuilder(), add = new StringBuilder();
        int i = 0, j = 0;
        while (i < la && j < lb) {
            if (midA.charAt(i) == midB.charAt(j)) {
                flushBlock(rem, add, out); i++; j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) rem.append(midA.charAt(i++));
            else add.append(midB.charAt(j++));
        }
        while (i < la) rem.append(midA.charAt(i++));
        while (j < lb) add.append(midB.charAt(j++));
        flushBlock(rem, add, out);
        return out;
    }

    static void flushBlock(StringBuilder rem, StringBuilder add, ArrayList<String[]> out) {
        if (rem.length() == 0 && add.length() == 0) return;
        out.add(new String[]{rem.toString(), add.toString()});
        rem.setLength(0);
        add.setLength(0);
    }

    /* ============================ 汇总表 ============================ */

    static ArrayList<Row> pick(String kind, boolean suspiciousOnly) {
        ArrayList<Row> out = new ArrayList<Row>();
        for (int i = 0; i < ROWS.size(); i++) {
            Row r = ROWS.get(i);
            if (!r.kind.equals(kind)) continue;
            if (suspiciousOnly && !r.suspBefore) continue;
            out.add(r);
        }
        return out;
    }

    static ArrayList<Row> pool(String poolName) {
        ArrayList<Row> out = new ArrayList<Row>();
        for (int i = 0; i < ROWS.size(); i++)
            if (ROWS.get(i).pool.equals(poolName) && ROWS.get(i).suspBefore) out.add(ROWS.get(i));
        return out;
    }

    static ArrayList<Row> pickIn(String kind, String poolName) {
        ArrayList<Row> out = new ArrayList<Row>();
        for (int i = 0; i < ROWS.size(); i++)
            if (ROWS.get(i).kind.equals(kind) && ROWS.get(i).pool.equals(poolName)
                    && ROWS.get(i).suspBefore) out.add(ROWS.get(i));
        return out;
    }

    static void stats(String label, ArrayList<Row> rows) {
        int n = rows.size(), changed = 0, washed = 0, worse = 0, noOpt = 0, digitLost = 0;
        int citeLost = 0, punctLost = 0, sentChanged = 0, dupNew = 0, bigDel = 0, sameScore = 0;
        double sb = 0d, sa = 0d, pct = 0d, blocks = 0d, charsB = 0d, charsA = 0d;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            sb += r.before; sa += r.after;
            charsB += r.rawBefore; charsA += r.rawAfter;
            pct += (r.rawAfter - r.rawBefore) * 100d / Math.max(1, r.rawBefore);
            blocks += r.blocks;
            if (r.changed) changed++;
            if (r.noOptions) noOpt++;
            if (r.blocks == 0) sameScore++;
            if (r.suspBefore && !r.suspAfter) washed++;
            if (r.after > r.before + 1e-9) worse++;
            if (r.digitsAfter < r.digitsBefore) digitLost++;
            if (r.citeAfter < r.citeBefore) citeLost++;
            if (r.punctAfter < r.punctBefore) punctLost++;
            if (r.sentsAfter != r.sentsBefore) sentChanged++;
            if (r.dupAfter > r.dupBefore) dupNew++;
            if (r.bigDeletions > 0) bigDel++;
        }
        double d = n == 0 ? 0d : 1d / n;
        System.out.println(String.format(Locale.US,
                "# %-16s 可疑段%3d 无处下笔%3d 有改动%3d 零改动%3d 洗白%3d 分数反升%3d",
                label, Integer.valueOf(n), Integer.valueOf(noOpt), Integer.valueOf(changed),
                Integer.valueOf(sameScore), Integer.valueOf(washed), Integer.valueOf(worse)));
        System.out.println(String.format(Locale.US,
                "# %16s 平均段分 %.3f -> %.3f (%+.3f) 平均字数 %+.2f%% 平均每段改动点 %.1f",
                " ", Double.valueOf(sb * d), Double.valueOf(sa * d), Double.valueOf((sa - sb) * d),
                Double.valueOf(pct * d), Double.valueOf(blocks * d)));
        System.out.println(String.format(Locale.US,
                "# %16s 丢数字%2d 丢文献标注%2d 丢标点%2d 句数变化%2d 新增重复句%2d 删除>=4字%2d",
                " ", Integer.valueOf(digitLost), Integer.valueOf(citeLost), Integer.valueOf(punctLost),
                Integer.valueOf(sentChanged), Integer.valueOf(dupNew), Integer.valueOf(bigDel)));
    }

    static void tables() {
        System.out.println();
        System.out.println("# 表 1：段级账（仪器 = AigcRhythm.measure，与打分同一份系数）");
        System.out.println("# 1a 单可疑段文档：一篇只有一个改动点，先把降重本身量干净");
        stats("机器段", pick("机器段", true));
        stats("  M-RAW", pickIn("机器段", "M-RAW"));
        stats("  M-EVADE", pickIn("机器段", "M-EVADE"));
        stats("  M-DOMAIN", pickIn("机器段", "M-DOMAIN"));
        stats("  M-DRLX", pickIn("机器段", "M-DRLX"));
        System.out.println();
        stats("真人段", pick("真人段", true));
        stats("  H-THESIS", pickIn("真人段", "H-THESIS"));
        stats("  H-ABS", pickIn("真人段", "H-ABS"));
        stats("  H-DRLX", pickIn("真人段", "H-DRLX"));
        stats("  X-POLISH", pickIn("真人段", "X-POLISH"));
        System.out.println();
        System.out.println("# 1b 自然成篇（一篇里可能有好几个可疑段，偏移会互相影响）");
        stats("机器段-多篇", pick("机器段-多篇", true));
        stats("真人段-多篇", pick("真人段-多篇", true));
        stats("配对混排-机器段", pickRows("配对混排", "M-DRLX"));
        stats("配对混排-真人段", pickRows("配对混排", "H-DRLX"));

        System.out.println();
        System.out.println("# 表 2：整篇账（产品自己报的那几个数）");
        docs("机器段");
        docs("真人段");
        docs("机器段-多篇");
        docs("真人段-多篇");
        docs("配对混排");

        System.out.println();
        System.out.println("# 表 3：段分变化出在哪几条判据上（仅统计真正被改动的段）");
        double[] sum = new double[AigcRhythm.CRITERIA];
        int used = 0, sentChanged = 0, digitChanged = 0, citeChanged = 0;
        for (int i = 0; i < ROWS.size(); i++) {
            Row r = ROWS.get(i);
            if (!r.changed) continue;
            used++;
            if (r.sentsAfter != r.sentsBefore) sentChanged++;
            if (r.digitsAfter != r.digitsBefore) digitChanged++;
            if (r.citeAfter != r.citeBefore) citeChanged++;
            for (int k = 0; k < AigcRhythm.CRITERIA; k++) sum[k] += r.contribAfter[k] - r.contribBefore[k];
        }
        Integer[] order = new Integer[AigcRhythm.CRITERIA];
        for (int k = 0; k < AigcRhythm.CRITERIA; k++) order[k] = Integer.valueOf(k);
        java.util.Arrays.sort(order, new Comparator<Integer>() {
            public int compare(Integer x, Integer y) {
                return Double.compare(Math.abs(sum[y.intValue()]), Math.abs(sum[x.intValue()]));
            }
        });
        System.out.println("#   被改动的段 " + used + " 个：句数变化 " + sentChanged
                + " 段 · 数字个数变化 " + digitChanged + " 段 · 文献标注变化 " + citeChanged + " 段");
        for (int t = 0; t < AigcRhythm.CRITERIA; t++) {
            int k = order[t].intValue();
            System.out.println(String.format(Locale.US, "#   %-22s 平均贡献变化 %+.4f",
                    criterionName(k), Double.valueOf(used == 0 ? 0d : sum[k] / used)));
        }

        System.out.println();
        System.out.println("# 表 4：采纳的改写到底动了哪几个字（按出现次数排，前 40 条）");
        ArrayList<Map.Entry<String, Integer>> list =
                new ArrayList<Map.Entry<String, Integer>>(TOUCHES.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, Integer>>() {
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue().intValue() - a.getValue().intValue();
            }
        });
        for (int i = 0; i < list.size() && i < 40; i++)
            System.out.println("#   " + list.get(i).getValue() + " 次  " + list.get(i).getKey());
        System.out.println("#   合计不同改动字面 " + list.size() + " 种");
    }

    static ArrayList<Row> pickRows(String kind, String poolName) {
        ArrayList<Row> out = new ArrayList<Row>();
        for (int i = 0; i < ROWS.size(); i++)
            if (ROWS.get(i).kind.equals(kind) && ROWS.get(i).pool.equals(poolName)
                    && ROWS.get(i).suspBefore) out.add(ROWS.get(i));
        return out;
    }

    static void docs(String kind) {
        int n = 0, crash = 0, rolled = 0, badFid = 0, collateral = 0, shareOnly = 0, grew = 0;
        int flaggedSame = 0, multi = 0, driftDocs = 0, drift = 0, swallow = 0, spill = 0;
        int regions = 0, adopted = 0, verif = 0;
        double millis = 0d, flaggedB = 0d, flaggedA = 0d, charsB = 0d, charsA = 0d, idxB = 0d, idxA = 0d;
        for (int i = 0; i < DOCS.size(); i++) {
            DocRecord d = DOCS.get(i);
            if (!d.kind.equals(kind)) continue;
            n++;
            if (d.error.length() > 0) { crash++; continue; }
            if (d.suspectParas > 1) multi++;
            if (d.rolledBack) rolled++;
            if (!d.applyFidelity) badFid++;
            if (d.collateral != 0) collateral++;
            regions += d.regions; adopted += d.adopted; verif += d.verifications;
            millis += d.millis;
            flaggedB += d.flaggedBefore; flaggedA += d.flaggedAfter;
            charsB += d.charsBefore; charsA += d.charsAfter;
            idxB += d.indexBefore; idxA += d.indexAfter;
            double shareB = d.charsBefore <= 0 ? 0d : d.flaggedBefore * 100d / d.charsBefore;
            double shareA = d.charsAfter <= 0 ? 0d : d.flaggedAfter * 100d / d.charsAfter;
            if (d.flaggedAfter == d.flaggedBefore && shareA < shareB - 0.05d) shareOnly++;
            if (d.charsAfter > d.charsBefore) grew++;
            if (d.flaggedAfter == d.flaggedBefore) flaggedSame++;
            if (d.applyDelta != 0) driftDocs++;
            drift += Math.abs(d.applyDelta);
            if (d.applyDelta > 0) spill += d.applyDelta; else swallow += -d.applyDelta;
        }
        double dd = n == 0 ? 0d : 1d / n;
        System.out.println(String.format(Locale.US,
                "# %-10s 篇数%3d 抛异常%2d 多可疑篇%2d 可疑段%3d 采纳%3d 复测%4d次 平均%5.0fms 整篇退回%2d 落笔不一致%2d 误伤篇%2d",
                kind, Integer.valueOf(n), Integer.valueOf(crash), Integer.valueOf(multi),
                Integer.valueOf(regions), Integer.valueOf(adopted),
                Integer.valueOf(verif), Double.valueOf(millis * dd), Integer.valueOf(rolled),
                Integer.valueOf(badFid), Integer.valueOf(collateral)));
        System.out.println(String.format(Locale.US,
                "# %8s 平均可疑字数 %.0f -> %.0f · 平均计分字数 %.0f -> %.0f (%+.2f%%) · 平均指数 %.3f -> %.3f",
                " ", Double.valueOf(flaggedB * dd), Double.valueOf(flaggedA * dd),
                Double.valueOf(charsB * dd), Double.valueOf(charsA * dd),
                Double.valueOf(charsB == 0 ? 0d : (charsA - charsB) * 100d / charsB),
                Double.valueOf(idxB * dd), Double.valueOf(idxA * dd)));
        System.out.println(String.format(Locale.US,
                "# %10s 可疑字数一字未少的篇数%2d · 其中占比照样下降%2d · 计分字数变大的篇数%2d",
                " ", Integer.valueOf(flaggedSame), Integer.valueOf(shareOnly), Integer.valueOf(grew)));
        System.out.println(String.format(Locale.US,
                "# %10s 落笔与复测不一致%2d 篇，共差 %d 字（吞正文 %d / 留残渣 %d）",
                " ", Integer.valueOf(driftDocs), Integer.valueOf(drift),
                Integer.valueOf(swallow), Integer.valueOf(spill)));
        int zN = 0, zero = 0;
        double zB = 0d, zA = 0d;
        for (int i = 0; i < DOCS.size(); i++) {
            DocRecord d = DOCS.get(i);
            if (!d.kind.equals(kind) || d.error.length() > 0) continue;
            if (d.emptyRegions) { zero++; continue; }
            zN++; zB += d.charsBefore; zA += d.charsAfter;
        }
        System.out.println(String.format(Locale.US,
                "# %10s 无可疑段%2d 篇（改后读数被报成 0）· 只算有可疑段的 %d 篇：平均计分字数 %.0f -> %.0f (%+.2f%%)",
                " ", Integer.valueOf(zero), Integer.valueOf(zN),
                Double.valueOf(zN == 0 ? 0d : zB / zN), Double.valueOf(zN == 0 ? 0d : zA / zN),
                Double.valueOf(zB == 0 ? 0d : (zA - zB) * 100d / zB)));
    }

    /* ============================ 三个改前/改后对照样例 ============================ */

    static void samples() {
        System.out.println();
        System.out.println("# 表 5：改前 / 改后对照（三段都是产品真采纳、正文真落笔的段，互不重复）");
        Row a = bestUnused(new Filter() {
            public boolean ok(Row r) { return r.changed && r.kind.startsWith("机器")
                    && r.suspBefore && r.suspAfter; }
        }, 0);
        Row b = bestUnused(new Filter() {
            public boolean ok(Row r) { return r.changed && r.kind.startsWith("机器")
                    && r.suspBefore && !r.suspAfter; }
        }, 1);
        if (b == null) b = bestUnused(new Filter() {
            public boolean ok(Row r) { return r.changed && r.suspBefore && !r.suspAfter; }
        }, 1);
        if (b == null) b = bestUnused(new Filter() {
            public boolean ok(Row r) { return r.changed && r.suspBefore && r.after < r.before; }
        }, 1);
        Row c = bestUnused(new Filter() {
            public boolean ok(Row r) { return r.changed && r.kind.startsWith("真人")
                    && r.pool.equals("H-THESIS"); }
        }, 0);
        if (c == null) c = bestUnused(new Filter() {
            public boolean ok(Row r) { return r.changed && r.kind.startsWith("真人")
                    && r.pool.equals("H-DRLX"); }
        }, 0);
        if (c == null) c = bestUnused(new Filter() {
            public boolean ok(Row r) { return r.changed && r.pool.startsWith("H") && r.suspBefore; }
        }, 0);
        print("样例 A（机器段：改完仍可疑）", a);
        print("样例 B（改完从可疑翻成不可疑：谁被洗白看这里的池子）", b);
        print("样例 C（真人段：本来就该一字不动）", c);
    }

    /** 贴过一遍的段不再重复贴（同一篇正文在不同实验里会各记一笔，按原文文本去重）。 */
    static final ArrayList<Row> SAMPLED = new ArrayList<Row>();
    static final ArrayList<String> SAMPLED_TEXT = new ArrayList<String>();

    static Row bestUnused(Filter filter, int mode) {
        Row out = null;
        double key = 0d;
        for (int i = 0; i < ROWS.size(); i++) {
            Row r = ROWS.get(i);
            if (!filter.ok(r) || SAMPLED_TEXT.contains(r.original)) continue;
            double v = mode == 0 ? r.blocks : r.before - r.after;
            if (out == null || v > key) { out = r; key = v; }
        }
        if (out != null) { SAMPLED.add(out); SAMPLED_TEXT.add(out.original); }
        return out;
    }

    interface Filter { boolean ok(Row row); }

    /** 取满足条件的一行；mode 0 = 改动点最多，1 = 段分降幅最大。 */
    static Row best(Filter filter, int mode) {
        Row out = null;
        double key = 0d;
        for (int i = 0; i < ROWS.size(); i++) {
            Row r = ROWS.get(i);
            if (!filter.ok(r)) continue;
            double v = mode == 0 ? r.blocks : r.before - r.after;
            if (out == null || v > key) { out = r; key = v; }
        }
        return out;
    }

    static void print(String title, Row r) {
        System.out.println();
        System.out.println("==== " + title + " ====");
        if (r == null) { System.out.println("（没有符合这一条的样例）"); return; }
        System.out.println(String.format(Locale.US, "来源 %s / %s · doc%02d 第 %d 段 · 可疑线 %.4f",
                r.kind, r.pool, Integer.valueOf(r.doc), Integer.valueOf(r.line),
                Double.valueOf(AigcRhythm.humanP99())));
        System.out.println(String.format(Locale.US, "段分 %.4f -> %.4f (%+.4f) · 可疑=%s -> %s · 有效字 %d -> %d · 句数 %d -> %d · 改动点 %d",
                Double.valueOf(r.before), Double.valueOf(r.after),
                Double.valueOf(r.after - r.before), Boolean.valueOf(r.suspBefore),
                Boolean.valueOf(r.suspAfter), Integer.valueOf(r.charsBefore),
                Integer.valueOf(r.charsAfter), Integer.valueOf(r.sentsBefore),
                Integer.valueOf(r.sentsAfter), Integer.valueOf(r.blocks)));
        System.out.println("产品账上的段分：" + f(r.appBefore, 4) + " -> " + f(r.appAfter, 4)
                + (Math.abs(r.appBefore - r.before) < 1e-9 ? "（改前与复算一致）" : "（改前与复算不一致！）"));
        System.out.println("改前：" + r.original);
        System.out.println("改后：" + r.adoptedText);
        StringBuilder blocks = new StringBuilder();
        for (int i = 0; i < r.blockList.size(); i++) {
            if (i > 0) blocks.append(" | ");
            blocks.append(r.blockList.get(i));
        }
        System.out.println("动了哪些字：" + blocks);
        System.out.println("产品是否把这段落笔进正文：" + (r.changed ? "是（下面这份改后就在线上正文里）" : "否"));
        System.out.println("账上留给用户的理由：" + (r.reason == null || r.reason.length() == 0 ? "（无）" : r.reason)
                + (r.reasonConflict ? "　<< 与落笔矛盾" : ""));
    }

    /* ============================ 落盘 ============================ */

    static void writeTsv() throws IOException {
        new File(OUT_DIR).mkdirs();
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(new File(OUT_DIR, "audit-regions.tsv")), "UTF-8"));
        w.write("kind\tpool\tdoc\tline\tchanged\tscore_before\tscore_after\tsusp_before\tsusp_after"
                + "\traw_before\traw_after\tchars_before\tchars_after\tsent_before\tsent_after"
                + "\tdigits_before\tdigits_after\tcite_before\tcite_after\tpunct_before\tpunct_after"
                + "\tdup_before\tdup_after\tblocks\tremoved\tadded\tbig_del\tno_options\treason"
                + "\toriginal\tadopted\n");
        for (int i = 0; i < ROWS.size(); i++) {
            Row r = ROWS.get(i);
            w.write(String.join("\t", r.kind, r.pool, String.valueOf(r.doc), String.valueOf(r.line),
                    String.valueOf(r.changed), f(r.before, 4), f(r.after, 4),
                    String.valueOf(r.suspBefore), String.valueOf(r.suspAfter),
                    String.valueOf(r.rawBefore), String.valueOf(r.rawAfter),
                    String.valueOf(r.charsBefore), String.valueOf(r.charsAfter),
                    String.valueOf(r.sentsBefore), String.valueOf(r.sentsAfter),
                    String.valueOf(r.digitsBefore), String.valueOf(r.digitsAfter),
                    String.valueOf(r.citeBefore), String.valueOf(r.citeAfter),
                    String.valueOf(r.punctBefore), String.valueOf(r.punctAfter),
                    String.valueOf(r.dupBefore), String.valueOf(r.dupAfter),
                    String.valueOf(r.blocks), String.valueOf(r.removed), String.valueOf(r.added),
                    String.valueOf(r.bigDeletions), String.valueOf(r.noOptions),
                    flat(r.reason), flat(r.original), flat(r.adoptedText)));
            w.write("\n");
        }
        w.close();
        BufferedWriter d = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(new File(OUT_DIR, "audit-docs.tsv")), "UTF-8"));
        d.write("kind\tdoc\tmeasured\tindex_before\tindex_after\tflagged_before\tflagged_after"
                + "\tchars_before\tchars_after\tregions\tadopted\tverifications\tmillis"
                + "\trolled_back\tcollateral\tapply_fidelity\tverdict\n");
        for (int i = 0; i < DOCS.size(); i++) {
            DocRecord r = DOCS.get(i);
            d.write(String.join("\t", r.kind, String.valueOf(r.index), String.valueOf(r.measured),
                    f(r.indexBefore, 4), f(r.indexAfter, 4), String.valueOf(r.flaggedBefore),
                    String.valueOf(r.flaggedAfter), String.valueOf(r.charsBefore),
                    String.valueOf(r.charsAfter), String.valueOf(r.regions), String.valueOf(r.adopted),
                    String.valueOf(r.verifications), String.valueOf(r.millis),
                    String.valueOf(r.rolledBack), String.valueOf(r.collateral),
                    String.valueOf(r.applyFidelity), flat(r.verdict)));
            d.write("\n");
        }
        d.close();
        System.out.println();
        System.out.println("# 明细落盘：" + OUT_DIR + "/audit-regions.tsv（" + ROWS.size() + " 段） · "
                + OUT_DIR + "/audit-docs.tsv（" + DOCS.size() + " 篇）");
    }

    static String flat(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /* ============================ 自动缺陷体检 ============================ */

    static void defects() {
        int changed = 0, worse = 0, digitLost = 0, citeLost = 0, punctLost = 0, sentChanged = 0;
        int dupNew = 0, bigDel = 0, zeroBlock = 0, mismatch = 0, noOpt = 0;
        double maxShrink = 0d;
        for (int i = 0; i < ROWS.size(); i++) {
            Row r = ROWS.get(i);
            if (r.changed) changed++;
            if (r.noOptions) noOpt++;
            if (!r.changed) continue;
            if (r.after > r.before + 1e-9) worse++;
            if (r.digitsAfter < r.digitsBefore) digitLost++;
            if (r.citeAfter < r.citeBefore) citeLost++;
            if (r.punctAfter < r.punctBefore) punctLost++;
            if (r.sentsAfter != r.sentsBefore) sentChanged++;
            if (r.dupAfter > r.dupBefore) dupNew++;
            if (r.bigDeletions > 0) bigDel++;
            if (r.blocks == 0) zeroBlock++;
            maxShrink = Math.max(maxShrink, (r.rawBefore - r.rawAfter) * 100d / Math.max(1, r.rawBefore));
            if (Math.abs(r.appBefore - r.before) > 1e-6) mismatch++;
        }
        int badFid = 0, collateralDocs = 0, shareOnly = 0, broke = 0, spent = 0;
        for (int i = 0; i < DOCS.size(); i++) {
            DocRecord d = DOCS.get(i);
            if (!d.applyFidelity) badFid++;
            if (d.collateral != 0) collateralDocs++;
            if (d.verifications >= LIMITS.verifications) spent++;
            double shareB = d.charsBefore <= 0 ? 0d : d.flaggedBefore * 100d / d.charsBefore;
            double shareA = d.charsAfter <= 0 ? 0d : d.flaggedAfter * 100d / d.charsAfter;
            if (d.flaggedAfter == d.flaggedBefore && shareA < shareB - 0.05d) shareOnly++;
            if (d.adopted > 0 && d.flaggedAfter >= d.flaggedBefore && d.charsAfter > d.charsBefore) broke++;
        }
        int emptyZeroed = 0;
        for (int i = 0; i < DOCS.size(); i++) {
            DocRecord d = DOCS.get(i);
            if (d.emptyRegions && (d.charsAfter == 0 || d.indexAfter == 0d)) emptyZeroed++;
        }
        int reasonLies = 0;
        for (int i = 0; i < ROWS.size(); i++) if (ROWS.get(i).reasonConflict) reasonLies++;
        int crashed = 0;
        for (int i = 0; i < DOCS.size(); i++) if (DOCS.get(i).error.length() > 0) crashed++;
        System.out.println();
        System.out.println("# 表 6：自动缺陷体检（数字 0 = 这一项没抓到问题）");
        line("D-0", "AigcRewrite.run 直接抛异常的篇数（区间偏移串位）", crashed);
        line("D-0b", "串位定点探针：" + probeTried + " 对里抛异常的对数", probeCrashes);
        line("D-0c", "串位定点探针：切片错位（记账与正文对不上）的对数", probeShifted);
        line("D-1", "落笔回放与复测文本不一致（UI 写进正文的 != 复测量的那份）", badFid);
        if (fidelitySample.length > 0)
            for (int i = 0; i < fidelitySample.length; i++)
                System.out.println("#        " + fidelitySample[i]);
        line("D-2", "整篇里没被判可疑的段落被连带改动（篇数）", collateralDocs);
        line("D-3", "可疑字数一字未少、只靠分母把占比做低的篇数", shareOnly);
        line("D-4", "可疑字数没降下来却仍判采纳的篇数", broke);
        line("D-5", "采纳段里改完段分反而升高的段数", worse);
        line("D-6", "采纳段里数字变少的段数", digitLost);
        line("D-7", "采纳段里文献标注变少的段数", citeLost);
        line("D-8", "采纳段里断句标点变少的段数", punctLost);
        line("D-9", "采纳段里句数被改动的段数（换词规则不该动句数）", sentChanged);
        line("D-10", "采纳段里新增重复句的段数", dupNew);
        line("D-11", "采纳段里出现 >=4 字连续删除的段数（疑似删句/截断）", bigDel);
        line("D-12", "采纳段的单段最大缩水 " + f(maxShrink, 2) + "%（flaw 放行线 40%）",
                maxShrink > 5d ? 1 : 0);
        line("D-13", "段分账对不上（产品 indexBefore != 复算）的段数", mismatch);
        line("D-14", "整篇复测预算打满的篇数（" + LIMITS.verifications + " 次全篇检测用尽）", spent);
        line("D-15", "无可疑段的篇 measured=true 却把改后指数/字数报成 0", emptyZeroed);
        if (emptyVerdictSample.length() > 0)
            System.out.println("#        产品原话：" + emptyVerdictSample);
        line("D-16", "账上已采纳落笔、理由却仍写不改的段数（报告与正文自相矛盾）", reasonLies);
        System.out.println("#   参考：可疑段总数 " + ROWS.size() + " · 有改动 " + changed
                + " · 零改动但被算采纳 " + zeroBlock + " · 无处下笔 " + noOpt);
    }

    static void line(String id, String what, int count) {
        System.out.println(String.format("#   %-5s %-52s %d %s", id, what, Integer.valueOf(count),
                count == 0 ? "OK" : "<< 有问题"));
    }
}

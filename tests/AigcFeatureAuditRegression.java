package com.rikkahub.wordlite;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * 逐特征的方向审计：拿带标注的语料量每一条特征在"真人 / 机器"两侧各排多高，并把结论钉在这里。
 *
 * 为什么要钉一个不好看的数：`docs/aigc-corpus.md` 4.2 节量到 AUC(真人, 机器) = 0.305——**方向是反的**，
 * 而 4.1 节同时量到真人侧 H2（多作者逐字摘录）最高句分 0.506 已经越过 `SEGMENT_FLAG_GATE = 0.45`，
 * 也就是说这一版判据在真人稿上会误报、在机器稿上一枪没打（机器侧最高 0.217）。这两条是这个功能
 * 现在唯一诚实的画像，所以它们进闸门而不是进注释：谁把 `AigcScorer.VERSION` 换成"标定过"的号、
 * 或者把某个特征的方向悄悄扳回来，这个套件会先炸，逼他带着新语料与新数字来改这里。
 *
 * 语料缺失一律判失败（不许静默跳过，ROADMAP 3.0.0 那条门槛的原意）：没有标注样本就没有审计，
 * 没有审计就没有资格报任何 AI 比例。
 *
 * 后半段还钉着另一件事：这份"没资格"必须已经落到对外出口上。这里故意造一份句分 45.3、档位齐备的
 * 报告，断言 HTML 里搜不到那个 45.3、AIGC 那一节一个百分号也没有、句子表自己声明顺序不是排序。
 * 方向审计与出口降级合在一个套件里，是因为它们必须同生同死：只改文案不改审计，或者只写审计不改文案，
 * 都会让这里重新变成一个好看的注释。
 *
 * 口径与产品同源：句子切分走 `TextCorpus.sentences`，段与文档统计走 `AigcFeatures.segment/stats`，
 * 句分走 `AigcScorer.score(hits, AigcScorer.current(family))`——与 `AigcDetector.detectInto` 逐行同序。
 */
public final class AigcFeatureAuditRegression {
    private static int checks;
    private static final int MIN_PARA_CHARS = 120;
    /** {代号, 文件, 侧别, 来源说明（进表格，标定台那张表欠的"样本来源"列在这里补上）} */
    private static final String[][] TIERS = {
            {"H1", "tests/corpus/real-prose.txt", "HUMAN", "学位论文正文，单一作者，从 input-liu.docx 抽取"},
            {"H2", "tests/corpus/aigc-label-human-verbatim.txt", "HUMAN", "已发表论文摘要逐字摘录，20 组作者，取自仓库内维普快照"},
            {"H3", "tests/corpus/cnki-cross.txt", "HUMAN", "知网检索页摘要预览截断片段，15 组"},
            {"X1", "tests/corpus/aigc-label-human-polish-agent.txt", "HUMAN", "真人原文 + 代理轻度润色（边界档，算在真人侧）"},
            {"M-RAW", "tests/corpus/aigc-label-machine-raw.txt", "MACHINE", "代理（LLM）生成，未经任何编辑"},
            {"M-EVADE", "tests/corpus/aigc-label-machine-evasive.txt", "MACHINE", "代理（LLM）生成，提示词要求规避风格"},
    };
    /** 与 AigcDetector 同门槛，抄成常量是为了让断言读得懂。 */
    private static final double GATE = AigcDetector.SEGMENT_FLAG_GATE;

    /** 一个计分句：总分 + 11 条特征的原值（不是命中与否，值才有 AUC 可算）。 */
    private static final class Row {
        double score;
        final double[] value = new double[AigcFeatureId.COUNT];
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }

    private static ArrayList<String> paragraphs(File f) throws Exception {
        ArrayList<String> out = new ArrayList<String>();
        try (InputStream in = new FileInputStream(f);
             BufferedReader read = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")))) {
            String line;
            while ((line = read.readLine()) != null) {
                String text = line.trim();
                // 口径逐字照 AigcCalibrationSweep.load()：先 trim 再判 #、空行与 120 字门槛。
                // 不 trim 会把行首行尾空白算进长度，H1 要多读进 2 句（实测 290 对标定台的 288）。
                if (text.isEmpty() || text.startsWith("#")) continue;
                if (TextCorpus.normalize(text).length() < MIN_PARA_CHARS) continue;
                out.add(text);
            }
        }
        return out;
    }

    /** 与 AigcDetector.detectInto 同一条流水线，只是把每条特征的原值也留下。 */
    private static void scan(String text, ArrayList<Row> into) {
        String norm = TextCorpus.normalize(text);
        ArrayList<int[]> spans = TextCorpus.sentences(text);
        ArrayList<AigcFeatures.Segment> scored = new ArrayList<AigcFeatures.Segment>();
        for (int i = 0; i < spans.size(); i++) {
            AigcFeatures.Segment seg = AigcFeatures.segment(norm, spans.get(i)[0], spans.get(i)[1]);
            if (!AigcFeatures.scores(seg)) continue;
            scored.add(seg);
        }
        AigcFeatures.DocStats stats = AigcFeatures.stats(norm, spans, scored);
        for (int i = 0; i < scored.size(); i++) {
            AigcFeatures.Segment seg = scored.get(i);
            ArrayList<AigcFeatures.Hit> hits = AigcFeatures.of(seg, stats);
            Row row = new Row();
            row.score = AigcScorer.score(hits, AigcScorer.current(seg.family));
            AigcFeatureId[] ids = AigcFeatureId.values();
            for (int k = 0; k < ids.length; k++) row.value[k] = AigcFeatures.value(ids[k], seg, stats);
            into.add(row);
        }
    }

    /** P(a > b)，并列算 0.5：Mann-Whitney 的 AUC，两侧谁当 a 就互为补数。 */
    private static double auc(double[] a, double[] b) {
        double wins = 0d;
        for (int i = 0; i < a.length; i++)
            for (int k = 0; k < b.length; k++) {
                if (a[i] > b[k]) wins += 1d;
                else if (a[i] == b[k]) wins += 0.5d;
            }
        return wins / ((double) a.length * (double) b.length);
    }

    private static double[] scoresOf(ArrayList<Row> rows) {
        double[] out = new double[rows.size()];
        for (int i = 0; i < out.length; i++) out[i] = rows.get(i).score;
        return out;
    }
    private static double[] valuesOf(ArrayList<Row> rows, int feature) {
        double[] out = new double[rows.size()];
        for (int i = 0; i < out.length; i++) out[i] = rows.get(i).value[feature];
        return out;
    }
    private static double maxOf(double[] v) {
        double m = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < v.length; i++) if (v[i] > m) m = v[i];
        return m;
    }
    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    public static void main(String[] args) throws Exception {
        LinkedHashMap<String, ArrayList<Row>> byTier = new LinkedHashMap<String, ArrayList<Row>>();
        ArrayList<Row> human = new ArrayList<Row>(), machine = new ArrayList<Row>();
        for (int t = 0; t < TIERS.length; t++) {
            File f = new File(TIERS[t][1]);
            if (!f.isFile()) throw new AssertionError("标注语料缺失：" + TIERS[t][1]
                    + "（" + TIERS[t][0] + "）。没有带标注的语料就没有审计，这个套件不许静默跳过。");
            ArrayList<Row> rows = new ArrayList<Row>();
            for (String p : paragraphs(f)) scan(p, rows);
            if (rows.isEmpty()) throw new AssertionError(TIERS[t][1] + " 一个计分句都没有，语料被改坏了？");
            byTier.put(TIERS[t][0], rows);
            if ("HUMAN".equals(TIERS[t][2])) human.addAll(rows); else machine.addAll(rows);
        }
        check(true, "六档带标注语料全部读到了计分句，缺一个文件就直接失败："
                + human.size() + " 个人句 / " + machine.size() + " 个机器句");
        check("v1-order-only".equals(AigcScorer.VERSION),
                "系数版本还是 v1-order-only：这批语料不支持换号，换号必须先改这个套件里被钉住的方向结论");

        System.out.println("tier\tsentences\tmean\tp50\tmax\t侧别\t样本来源");
        double humanMax = 0d, machineMax = 0d;
        for (int t = 0; t < TIERS.length; t++) {
            ArrayList<Row> rows = byTier.get(TIERS[t][0]);
            double[] v = scoresOf(rows);
            java.util.Arrays.sort(v);
            double sum = 0d;
            for (int i = 0; i < v.length; i++) sum += v[i];
            double max = v[v.length - 1];
            if ("HUMAN".equals(TIERS[t][2])) humanMax = Math.max(humanMax, max); else machineMax = Math.max(machineMax, max);
            System.out.println(TIERS[t][0] + "\t" + v.length + "\t" + fmt(sum / v.length) + "\t"
                    + fmt(v[v.length / 2]) + "\t" + fmt(max) + "\t" + TIERS[t][2] + "\t" + TIERS[t][3]);
        }

        double aucMachineOverHuman = auc(scoresOf(machine), scoresOf(human));
        // 逐特征方向表：均值与中位数给"两侧各排多高"，AUC 给"逐句比谁赢"，方向一栏是这两者的结论。
        System.out.println("feature\tgroup\t真人均值\t机器均值\t真人中位\t机器中位\tAUC(机器>真人)\t真人触发率\t机器触发率\t方向");
        AigcFeatureId[] ids = AigcFeatureId.values();
        int flipped = 0, dead = 0;
        StringBuilder verified = new StringBuilder();
        for (int k = 0; k < ids.length; k++) {
            double[] h = valuesOf(human, k), m = valuesOf(machine, k);
            double a = auc(m, h);
            double fireH = fires(h), fireM = fires(m);
            if (a < 0.45d) flipped++;
            if (fireH + fireM <= 0d) dead++;
            String sign;
            if (fireH + fireM <= 0d) sign = "两侧都不触发，无据可审";
            else if (a >= 0.55d) {
                sign = "机器更高（方向正）";
                if (fireH > 0d && fireM > 0d) {
                    if (verified.length() > 0) verified.append('+');
                    verified.append(ids[k].name());
                }
            } else if (a <= 0.45d) sign = "真人更高（方向反）";
            else sign = "分不开";
            System.out.println(ids[k] + "\t" + AigcFeatureId.groupLabel(ids[k]) + "\t" + fmt(mean(h)) + "\t"
                    + fmt(mean(m)) + "\t" + fmt(median(h)) + "\t" + fmt(median(m)) + "\t" + fmt(a) + "\t"
                    + fmt(fireH) + "\t" + fmt(fireM) + "\t" + sign);
        }
        check(aucMachineOverHuman <= 0.45d,
                "总分方向仍然是反的：AUC(机器>真人)=" + fmt(aucMachineOverHuman)
                        + "（≤0.45 才算这条结论还成立；谁把它推到 0.55 以上，就来把这里和 docs/aigc-corpus.md 一起改掉）");
        check(flipped >= 3,
                "至少三条特征单独看也是反的（实测 " + flipped + " 条 / " + ids.length + " 条 AUC(机器>真人) < 0.45）："
                        + "反不是某一科的偶然，是这套特征的整体取向问题");
        check(humanMax >= GATE,
                "真人侧最高句分 " + fmt(humanMax) + " 已经越过门槛 " + fmt(GATE) + "：现判据会在真人稿上误报，这条不许被忘掉");
        check(machineMax < GATE,
                "机器侧最高句分 " + fmt(machineMax) + " 连门槛都没碰到：" + fmt(GATE) + " 门槛下这批机器稿一枪没打");
        check(dead <= 2, "两侧都不触发的死特征实测 " + dead + " 条（句首结构重复、字符二元组熵）："
                + "审计覆盖到 11 条里的 9 条，这两条没有语料可审，谁要保留它们就得先给它们造出语料");
        // 方向为正的特征只有零星几条，撑不起一个"可疑句排序"。这条断言就是"宁可拒绝给数，也不给排序"的依据：
        // 谁把够四条推成正了，这里会炸，那时才轮到把报告里的拒绝换成相对排序（docs/aigc-corpus.md 第九节）。
        check(verified.length() == 0 || verifiedChars(verified.toString()) * 4 < ids.length,
                "方向经带标注语料验证为正的特征只有 [" + (verified.length() == 0 ? "无" : verified.toString())
                        + "]，不足 11 条的四分之一，够不上对外给一个可疑句排序");
        // 第二半闸门：未标定这件事必须已经落在对外出口上。这里故意摆一个 45.3 的句分，它一个字符都不许出现。
        check(!AigcScorer.calibrated(),
                "AigcScorer.calibrated() 还是 false（VERSION=" + AigcScorer.VERSION + " 不带 cal- 前缀）："
                        + "报告与界面一律按量不到处理");
        StringBuilder doc = new StringBuilder();
        for (int i = 0; i < 14; i++)
            doc.append("综上所述，通过不断努力和持续改进，我们不仅完成了预定的研究任务，还积累了一批宝贵的实践经验。")
                    .append("首先我们需要明确目标，其次要制定详细的计划，然后落实到具体的责任人，最后建立有效的反馈机制。\n");
        AigcDetector.Result live = AigcDetector.detect(doc.toString());
        check(live.comparedChars >= AigcDetector.MIN_DOCUMENT_CHARS && !live.insufficientSample,
                "下面这份样本过了 400 字门槛（实测 " + live.comparedChars + " 字）：拒绝给数不可能是因为样本不足");
        check(live.verdict.contains("不给生成比例") && live.verdict.contains("方向已实测为反"),
                "detect 的结论句先认错再谈数：" + live.verdict);
        DuplicateEngine.Report rep = new DuplicateEngine.Report();
        rep.sourceText = doc.toString();
        rep.aigc = live;
        rep.aigcRate = 45.3d;
        check(DuplicateEngine.aigcUnmeasured(rep), "判据未标定与没跑同一档：这一轮算没量到");
        check(DuplicateEngine.aigcScoreLine(rep).isEmpty(), "句分那一格给空串，整格不画");
        check(DuplicateEngine.aigcTrend(rep).contains("判据未标定")
                        && DuplicateEngine.aigcTrend(rep).contains("真人句分比机器句分更高"),
                "机器生成倾向那一格自带方向：" + DuplicateEngine.aigcTrend(rep));
        String html = CheckReport.html("audit.docx", rep);
        String section = aigcSection(html);
        check(!html.contains("机器腔均分") && !html.contains("45.3"),
                "报告里找不到那个 45.3：均分那一格连名字一起撤掉");
        check(section.indexOf('%') < 0, "AIGC 小节整段没有百分号（含每句那一列），命中位 " + section.indexOf('%'));
        check(section.contains("不是可疑度排序"), "句子表明说这个顺序不是排序");
        check(ReportStore.recordFor(rep, "audit.docx").aigcScore == 0d,
                "存档侧也不留数：未量到的记录里 aigcScore 落 0，别的界面休想把它读回来");
        System.out.println("SUMMARY " + checks + " feature-audit assertions passed on the labelled corpus "
                + "(" + human.size() + " human / " + machine.size() + " machine scored sentences; "
                + "verdict: direction inverted, human false positive present, coefficients stay v1-order-only).");
    }

    private static double mean(double[] v) {
        double sum = 0d;
        for (int i = 0; i < v.length; i++) sum += v[i];
        return v.length == 0 ? 0d : sum / v.length;
    }

    private static double median(double[] v) {
        double[] copy = v.clone();
        java.util.Arrays.sort(copy);
        if (copy.length == 0) return 0d;
        return copy.length % 2 == 1 ? copy[copy.length / 2]
                : (copy[copy.length / 2 - 1] + copy[copy.length / 2]) / 2d;
    }

    /** verified 那一栏里特征名的个数，比字符串长度诚实。 */
    private static int verifiedChars(String names) {
        return names.isEmpty() ? 0 : names.split("\\+").length;
    }

    /** 取 HTML 里 AIGC 那一节（从它的小节标题到下一个 h2），要断言"这一节里没有百分号"就只能切出这一节。 */
    private static String aigcSection(String html) {
        int from = html.indexOf("<h2>AIGC 倾向句</h2>");
        if (from < 0) throw new AssertionError("报告里没有 AIGC 倾向句那一节，断言没法下：" + html.length());
        int next = html.indexOf("<h2>", from + 8);
        return next < 0 ? html.substring(from) : html.substring(from, next);
    }

    private static double fires(double[] v) {
        int n = 0;
        for (int i = 0; i < v.length; i++) if (v[i] > 0d) n++;
        return v.length == 0d ? 0d : (double) n / (double) v.length;
    }
}

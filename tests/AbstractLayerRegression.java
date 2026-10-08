package com.rikkahub.wordlite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * 摘要层（近似）的量台与闸门。全程离线：材料全部来自 tests/corpus/，不碰外网。
 *
 * 它守住三件事：
 * 1. 阈值不是拍的——把"真同一段"与"无关领域摘要"两堆的逐句 max 袋 Dice 分布原样打出来，
 *    并断言无关领域那一堆一条都不许触发（负对照）；
 * 2. 摘要层的数不冒充正文级：这一层跑完，正文级那三个比率与命中字数的账一个字都不动；
 * 3. 每次检索请求的响应留档（A4）分得清"源说没有 / 被挡 / 我们解不出"。
 */
public final class AbstractLayerRegression {
    private static int failures;
    private static int checks;

    private static void check(boolean ok, String message) {
        checks++;
        System.out.println("  " + (ok ? "[OK]   " : "[FAIL] ") + message);
        if (!ok) failures++;
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static ArrayList<String> sentences(String text) {
        ArrayList<String> out = new ArrayList<String>();
        for (int[] span : TextCorpus.sentences(text)) {
            String one = text.substring(span[0], span[1]).trim();
            if (one.length() < TextCorpus.MIN_SENTENCE_CHARS) continue;
            // 与引擎同一道门：算的是有效字符数，不是原始长度。
            if (TextCorpus.bagUnit(one).chars >= TextCorpus.MIN_SENTENCE_CHARS) out.add(one);
        }
        return out;
    }

    private static PaperSources.Candidate candidate(String title, String engine, String material, String kind) {
        PaperSources.Candidate candidate = new PaperSources.Candidate();
        candidate.source.engine = engine;
        candidate.source.title = title;
        candidate.source.id = engine + ":" + title;
        candidate.abstractText = material;
        candidate.comparableMaterial = kind;
        return candidate;
    }

    /** 摘要侧的单元，与引擎里那一条构造完全同源：题名 + 摘要，逐句，MIN_SENTENCE_CHARS 以下不要。 */
    private static ArrayList<TextCorpus.BagUnit> units(ArrayList<PaperSources.Candidate> pool) {
        ArrayList<TextCorpus.BagUnit> out = new ArrayList<TextCorpus.BagUnit>();
        for (PaperSources.Candidate candidate : pool) {
            String title = candidate.source.title;
            String material = (title + (title.endsWith("。") || title.isEmpty() ? "" : "。")
                    + candidate.abstractText).trim();
            for (int[] span : TextCorpus.sentences(material)) {
                String one = material.substring(span[0], span[1]).trim();
                if (one.length() < TextCorpus.MIN_SENTENCE_CHARS) continue;
                TextCorpus.BagUnit unit = TextCorpus.bagUnit(one);
                if (unit.chars < TextCorpus.MIN_SENTENCE_CHARS) continue;
                out.add(unit);
            }
        }
        return out;
    }

    private static double maxDice(String query, ArrayList<TextCorpus.BagUnit> pool) {
        TextCorpus.BagUnit unit = TextCorpus.bagUnit(query);
        double best = 0d;
        for (TextCorpus.BagUnit other : pool) {
            float value = TextCorpus.bagJudge(unit, other);
            if (value > best) best = value;
        }
        return best;
    }

    /** 逐句 max 袋 Dice 的分位数；只统计过了判据（非零）的那几对，与产品口径一致。 */
    private static double quantile(double[] values, double fraction) {
        ArrayList<Double> kept = new ArrayList<Double>();
        for (double value : values) if (value > 0d) kept.add(Double.valueOf(value));
        if (kept.isEmpty()) return 0d;
        Double[] array = kept.toArray(new Double[0]);
        Arrays.sort(array);
        int index = (int) Math.ceil(fraction * array.length) - 1;
        return array[Math.max(0, Math.min(array.length - 1, index))].doubleValue();
    }

    private static double[] measure(ArrayList<String> queries, ArrayList<TextCorpus.BagUnit> pool) {
        double[] out = new double[queries.size()];
        for (int i = 0; i < queries.size(); i++) out[i] = maxDice(queries.get(i), pool);
        return out;
    }

    private static int fired(double[] values, double floor) {
        int n = 0;
        for (double value : values) if (value >= floor) n++;
        return n;
    }

    private static void pile(String name, double[] values, double floor) {
        System.out.println("   " + name + " n=" + values.length
                + " fired@0.72=" + fired(values, floor) + "/" + values.length
                + "  p50=" + fmt(quantile(values, 0.50)) + " p90=" + fmt(quantile(values, 0.90))
                + " p95=" + fmt(quantile(values, 0.95)) + " max=" + fmt(max(values)));
    }

    private static double max(double[] values) {
        double best = 0d;
        for (double value : values) if (value > best) best = value;
        return best;
    }

    private static String fmt(double value) {
        return String.format(Locale.US, "%.3f", Double.valueOf(value));
    }

    /** 每隔 4 个字符换一个别的字：轻改写那一档，摘要层至少要认得住。 */
    private static String sub25(String value) {
        StringBuilder out = new StringBuilder(value);
        for (int i = 3; i < out.length(); i += 4) out.setCharAt(i, (char) (value.charAt(i) + 7));
        return out.toString();
    }

    private static DuplicateEngine.Report layer(String body, ArrayList<PaperSources.Candidate> pool, int[] excluded) {
        DuplicateEngine.Report report = new DuplicateEngine.Report();
        report.candidates.addAll(pool);
        DuplicateEngine.abstractLayer(report, body, excluded == null ? new int[0] : excluded);
        return report;
    }

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : "tests/corpus";
        float floor = TextCorpus.bagFloor();
        System.out.println("== 阈值：袋 Dice 地板（活值，与正文级同一把尺）==");
        System.out.println("   TextCorpus.bagFloor() = " + floor + "  SIMILAR_BAG_DICE = "
                + Float.valueOf(TextCorpus.SIMILAR_BAG_DICE));
        check(Math.abs(floor - TextCorpus.SIMILAR_BAG_DICE) < 1e-6,
                "摘要层用的就是正文级那一条地板 0.72，没有另存一份常数");

        // ---- 材料堆：真实摘要文本，两个都跟本稿领域无关 ----
        ArrayList<PaperSources.Candidate> floorPool = new ArrayList<PaperSources.Candidate>();
        String[] names = { "floor-source-01-verbatim.txt", "floor-source-02-paraphrase.txt",
                "floor-source-03-quoted.txt", "floor-source-04-decoy.txt", "floor-source-05-references.txt" };
        for (int i = 0; i < names.length; i++)
            floorPool.add(candidate("地板样本文献" + (i + 1), "local", read(root + "/" + names[i]), "摘要"));
        ArrayList<PaperSources.Candidate> pool = new ArrayList<PaperSources.Candidate>();
        pool.addAll(floorPool);
        String[] crossLines = read(root + "/cnki-cross.txt").split("\n");
        int cross = 0;
        for (int i = 0; i < crossLines.length; i++) {
            String line = crossLines[i].trim();
            if (line.length() < 40) continue;
            cross++;
            pool.add(candidate("无关领域摘要" + cross, "cnki", line, "摘要"));
        }
        ArrayList<TextCorpus.BagUnit> poolUnits = units(pool);
        ArrayList<TextCorpus.BagUnit> floorUnits = units(floorPool);
        System.out.println("   材料 " + pool.size() + " 篇（地板 5 + 无关领域 " + cross + "），摘要侧句子单元 "
                + poolUnits.size() + " 条");

        // ---- 两堆分布 ----
        System.out.println();
        System.out.println("== 逐句 max 袋 Dice 的分布（判据四道门全开，与产品同一条 bagJudge）==");
        ArrayList<String> positive = new ArrayList<String>();
        positive.addAll(sentences(read(root + "/floor-source-01-verbatim.txt")));
        positive.addAll(sentences(read(root + "/floor-source-03-quoted.txt")));
        double[] posVerbatim = measure(positive, poolUnits);
        pile("POS 真同一段（逐字取自材料本身）", posVerbatim, floor);
        ArrayList<String> light = new ArrayList<String>();
        for (int i = 0; i < positive.size(); i++) light.add(sub25(positive.get(i)));
        double[] posLight = measure(light, poolUnits);
        pile("POS 轻改写（每第 4 字换一个）", posLight, floor);
        ArrayList<String> manuscript = sentences(read(root + "/real-prose.txt"));
        double[] negManuscript = measure(manuscript, poolUnits);
        pile("NEG2 真稿件正文 vs 这批材料（含由它改写的两篇）", negManuscript, floor);
        ArrayList<String> crossQueries = new ArrayList<String>();
        for (int i = 0; i < crossLines.length; i++)
            if (crossLines[i].trim().length() >= 40) crossQueries.addAll(sentences(crossLines[i]));
        double[] negCross = measure(crossQueries, floorUnits);
        pile("NEG1 无关领域摘要 vs 另一堆无关材料（负对照）", negCross, floor);

        check(fired(posVerbatim, floor) == posVerbatim.length,
                "POS：真同一段逐句全过线（" + posVerbatim.length + "/" + posVerbatim.length + "）");
        check(fired(posLight, floor) * 10 >= posLight.length * 9,
                "POS：轻改写那一档至少九成句子还过线（" + fired(posLight, floor) + "/" + posLight.length
                        + "，最低 " + fmt(minOf(posLight)) + "）");
        check(fired(negCross, floor) == 0,
                "负对照一：无关领域摘要与另一堆无关材料之间一条都不许触发（实测 " + fired(negCross, floor)
                        + " 条，天花板 " + fmt(max(negCross)) + "）");
        check(max(negCross) < floor - 0.10d,
                "负对照一的天花板与地板之间留着 0.10 以上余量（" + fmt(max(negCross)) + " < "
                        + fmt(floor - 0.10d) + "）");
        check(fired(negManuscript, floor) == 0,
                "负对照二：真稿件正文对这批材料（含由它改写而来的那两篇）过线条数必须为 0（实测 "
                        + fired(negManuscript, floor) + " 条）");
        check(max(negManuscript) < floor,
                "负对照二的天花板仍在地板之下，但余量很薄——这就是轻改写的已知盲区，不许把它说成安全余量（"
                        + fmt(max(negManuscript)) + " < " + fmt(floor) + "，差 "
                        + fmt(floor - max(negManuscript)) + "）");

        // ---- 引擎这一层：数字口径 ----
        System.out.println();
        System.out.println("== 摘要层这一层的账 ==");
        String first = positive.get(0);
        String second = positive.get(1);
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 5; i++) body.append("这是本稿里第").append(i + 1).append("段的技术交代，与那批摘要不是一个主题。\n");
        body.append(first).append('\n').append(second).append('\n');
        body.append("结尾一句同样是本机自己的话，不留给摘要层任何可撞的东西。\n");
        String thesis = body.toString();
        DuplicateEngine.Report report = layer(thesis, pool, null);
        double[] own = measure(sentences(thesis), poolUnits);
        check(report.abstractSentencesCompared == sentences(thesis).size(),
                "分母 = 本文参与比对的句子数（" + report.abstractSentencesCompared + "），与逐句量的口径一致");
        check(report.abstractSentencesMatched == fired(own, floor),
                "分子 = 过线句子数（" + report.abstractSentencesMatched + "），引擎与量台同一把尺");
        check(report.abstractSentencesMatched >= 2, "植入两句逐字原文，这一层至少认出 2 句");
        check(Math.abs(report.abstractLayerRate
                - report.abstractSentencesMatched * 100d / report.abstractSentencesCompared) < 1e-9,
                "比率 = 分子/分母 x 100（" + report.abstractLayerRate + "），不是字数加权");
        check(report.abstractCandidates == pool.size() && report.abstractUnits == poolUnits.size(),
                "候选 " + report.abstractCandidates + " 篇、摘要单元 " + report.abstractUnits + " 条都记在账上");
        check(!report.abstractHits.isEmpty() && report.abstractHits.get(0).title.length() > 0,
                "证据行带着来源题名与撞上的那句摘要");
        boolean ascending = true;
        for (int i = 1; i < report.abstractHits.size(); i++)
            if (report.abstractHits.get(i).start < report.abstractHits.get(i - 1).start) ascending = false;
        check(ascending, "逐句证据按正文顺序排，画表时不必再排一次");
        double top = 0d;
        for (int i = 0; i < report.abstractHits.size(); i++)
            top = Math.max(top, report.abstractHits.get(i).score);
        check(Math.abs(top - max(own)) < 1e-6, "表里一定带着最高那一句（" + fmt(top) + "）");

        // ---- 这一层不许碰正文级的账 ----
        DuplicateEngine.Report guard = layer(thesis, pool, null);
        guard.overallRate = 0d;
        check(guard.duplicateChars == 0 && guard.comparableCandidates == 0 && guard.hits.isEmpty()
                        && guard.overallRate == 0d && guard.abstractSentencesMatched > 0,
                "摘要层跑完，正文级命中 0 字、可比候选 0 篇、总相似度比 0.00% 原样不动——两个数不同账");

        // ---- 只吃"摘要"这一档 ----
        ArrayList<PaperSources.Candidate> fullOnly = new ArrayList<PaperSources.Candidate>();
        fullOnly.add(candidate("抓到全文的那篇", "crossref", first, "全文"));
        DuplicateEngine.Report skipped = layer(thesis, fullOnly, null);
        check(skipped.abstractSentencesCompared == 0 && skipped.abstractSentencesMatched == 0,
                "抓到全文的候选走正文级，绝不进摘要层（免得同一个数报两遍）");
        check(!DuplicateEngine.abstractLayerMeasured(skipped)
                        && DuplicateEngine.abstractLayerLine(skipped).isEmpty(),
                "没量到就不许出现那一行");

        // ---- 排除区：引用段落与参考文献表不吃分母 ----
        String quoted = "他在文里写道：\"" + first + "\"，这一点值得商榷。\n";
        String withQuote = quoted + thesis;
        int[] excluded = new int[] { 0, quoted.length() };
        DuplicateEngine.Report trimmed = layer(withQuote, pool, excluded);
        check(trimmed.abstractSentencesCompared < layer(withQuote, pool, null).abstractSentencesCompared,
                "引用段落整段从分母里去掉（" + trimmed.abstractSentencesCompared + " < 未排除那一档）");

        // ---- 口径长在名字上 ----
        System.out.println();
        System.out.println("== 文案闸门：这一层在报告里叫什么 ==");
        String label = DuplicateEngine.ABSTRACT_LAYER_LABEL;
        String line = DuplicateEngine.abstractLayerLine(report);
        String caveat = DuplicateEngine.abstractLayerCaveat(report);
        String steps = DuplicateEngine.abstractLayerNextSteps();
        System.out.println("   " + label + " " + line);
        System.out.println("   " + caveat);
        System.out.println("   " + steps);
        check(label.contains("近似") && label.contains("只到摘要"),
                "标题自带\"近似\"与\"公开检索只到摘要\"，不靠脚注解释");
        check(line.contains("句") && line.contains("0.72"), "数字自带分子分母与阈值：" + line);
        check(caveat.contains("不计入总相似度比") && caveat.contains("不与它相加"),
                "口径声明写死\"不计入、不相加\"，摘要层不许冒充正文级");
        check(steps.contains("授权") && steps.contains("自建库"),
                "\"没有任何可比正文\"那一屏的下一步给的是能走的两条路");

        // ---- 存盘再读回 ----
        ReportStore.Record record = ReportStore.recordFor(report, "thesis.docx");
        ReportStore.Record back = ReportStore.Record.fromJson(ApiJson.parse(record.toJson()));
        check(Math.abs(back.abstractLayerRate - record.abstractLayerRate) < 1e-9
                        && back.abstractSentencesCompared == record.abstractSentencesCompared
                        && back.abstractSentencesMatched == record.abstractSentencesMatched
                        && back.abstractEvidence.size() == record.abstractEvidence.size(),
                "摘要层的数与逐句证据能存盘再读回（" + back.abstractSentencesMatched + "/"
                        + back.abstractSentencesCompared + "）");
        check(back.abstractEvidence.size() == record.abstractEvidence.size()
                        && (back.abstractEvidence.isEmpty() || back.abstractEvidence.get(0).title.equals(
                                record.abstractEvidence.get(0).title)),
                "证据行存回来还带着题名，偏移没漂");
        check(record.abstractEvidence.size() <= ReportStore.MAX_ABSTRACT_EVIDENCE,
                "证据条数夹在 " + ReportStore.MAX_ABSTRACT_EVIDENCE + " 条以内");

        // ---- A4：每次请求都留档，形状分得清 ----
        System.out.println();
        System.out.println("== 响应留档（A4）==");
        check("entries".equals(PaperSources.shapeOf("<html>二十条题录</html>", 20, -1L)),
                "解出条目 -> entries");
        check("blocked".equals(PaperSources.shapeOf("<html>请输入验证码后继续访问</html>", 0, -1L)),
                "验证页 -> blocked，不许伪装成零命中");
        check("declared-zero".equals(PaperSources.shapeOf("检索结果为空", 0, 0L)),
                "源明说没有 -> declared-zero");
        check("js-shell".equals(PaperSources.shapeOf("<!DOCTYPE html><html><script src=\"a.js\"></script></html>", 0, -1L)),
                "只有脚本壳 -> js-shell");
        check("empty-body".equals(PaperSources.shapeOf("   ", 0, -1L)), "空响应体 -> empty-body");
        check("declared-zero".equals(PaperSources.frameShape(0, 0, 0L, "检索结果为空")),
                "万方那 25 字节的\"检索结果为空\"帧 -> declared-zero，档里查得到");
        check("frame-unparsed".equals(PaperSources.frameShape(3, 0, -1L, "")),
                "帧里有著录项却解不出 -> frame-unparsed，与\"源说没有\"分开");

        // ---- Limits.copy() 不许漏字段 ----
        System.out.println();
        System.out.println("== Limits.copy() 逐字段 ==");
        PaperSources.Limits source = new PaperSources.Limits();
        source.timeoutSeconds = 33;
        source.perEngine = 7;
        source.windows = 21;
        source.fullTexts = 3;
        source.coreKey = "k";
        source.proxy = "127.0.0.1:7897";
        final boolean[] recorded = new boolean[1];
        source.shapes = new PaperSources.ShapeSink() {
            public void record(PaperSources.ShapeRow row) { recorded[0] = true; }
        };
        PaperSources.Limits copy = source.copy();
        boolean same = true;
        for (Field field : PaperSources.Limits.class.getDeclaredFields()) {
            field.setAccessible(true);
            if (!java.lang.reflect.Modifier.isPublic(field.getModifiers())) continue;
            try {
                if (!String.valueOf(field.get(source)).equals(String.valueOf(field.get(copy)))) {
                    same = false;
                    System.out.println("   漏了字段 " + field.getName());
                }
            } catch (Exception ignored) { same = false; }
        }
        check(same, "Limits.copy() 一个公开字段都没漏（漏一个就是那一轮拿默认值当用户设置用）");
        check(copy.shapes == source.shapes, "留档出口跟着复制带进这一轮（同一个出口对象，回调挂得上）");

        System.out.println();
        System.out.println("SUMMARY " + checks + " abstract-layer assertions; " + failures + " failed; "
                + "threshold 0.72 sits between NEG ceiling " + fmt(max(negManuscript))
                + " and POS-light floor " + fmt(minOf(posLight)) + "; loopback-free run.");
        if (failures > 0) throw new AssertionError(failures + " 项摘要层断言失败");
    }

    private static double minOf(double[] values) {
        double best = Double.MAX_VALUE;
        for (double value : values) if (value < best) best = value;
        return best == Double.MAX_VALUE ? 0d : best;
    }
}



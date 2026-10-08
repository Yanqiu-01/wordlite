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
 * 逐特征的方向审计 + "没资格出数就必须真的不出数"的闸门。
 *
 * 这个套件钉住四件事，缺一不可：
 * 1) 判据方向：拿带标注语料量每一条特征在"真人 / 机器"两侧各排多高，总分方向反着就钉"反着"。
 *    2026-10-08 这一轮的读数（七档池，真人 493 计分句 / 机器 262 计分句，其中 M-DOMAIN 是与真人同领域
 *    同主题配对的机器稿）：总分 AUC(机器>真人)=0.312，仍然是反的；真人侧最高句分 0.506 已越过
 *    SEGMENT_FLAG_GATE(0.450)，机器侧最高只有 0.217，一枪没打。
 * 2) 判据扩容这件事不能悄悄发生：本轮新增的 22 条候选（外部依据见 docs/oss-aigc-detection.md 与
 *    {@link AigcFeatureId} 逐条注释）在这里逐条量方向，并且逐条断言它们在 {@link AigcScorer#defaults(int)}
 *    里就是 0 系数。方向没验正就配权重，是这个套件最先炸的那种改法。
 * 3) "不够格换号"必须是量出来的，不是选的：套件自己按 {@link AigcScorer#check} 那两条静态纪律
 *    重跑一遍天花板表（{@code pwsh tools/aigc-fit.ps1 ceiling} 同一张），断言它把机器侧推到什么位置、
 *    留出档仍然一句都过不了线。谁想说"门槛定高了所以量不到"，这一条会先反对他。
 * 4) 未标定必须已经落到对外出口：故意造一份句分 45.3、档位齐备的报告，断言 HTML 里搜不到那个 45.3、
 *    AIGC 那一节一个百分号也没有、句子表自己声明顺序不是排序。
 *
 * 语料缺失一律判失败（不许静默跳过，ROADMAP 3.0.0 那条门槛的原意）：没有标注样本就没有审计，
 * 没有审计就没有资格报任何 AI 比例。
 *
 * 换号（VERSION 改成 cal-*）必须先在这里翻面，并且翻的时候要能把这四条一起重写：
 * 留出档 AUC(机器>真人) ≥ 0.750（拟合只能用 M-RAW + H1/H2/H3；M-EVADE、M-DOMAIN、X1、钉子户一律留出）
 * 且真人侧误报 ≤ 2 句/千句。本轮的实测是 0.657 / 0.0，前者不过，所以版本号继续是 v1-order-only。
 *
 * 口径与产品同源：句子切分走 `TextCorpus.sentences`，段与文档统计走 `AigcFeatures.segment/stats`，
 * 句分走 `AigcScorer.score(hits, AigcScorer.current(family))`——与 `AigcDetector.detectInto` 逐行同序。
 */
public final class AigcFeatureAuditRegression {
    private static int checks;
    private static final int MIN_PARA_CHARS = 120;
    /** {代号, 文件, 侧别, 拟合角色（FIT 参与拟合 / HOLD 只出不进）, 来源说明（进表格）} */
    private static final String[][] TIERS = {
            {"H1", "tests/corpus/real-prose.txt", "HUMAN", "FIT", "学位论文正文，单一作者，从 input-liu.docx 抽取"},
            {"H2", "tests/corpus/aigc-label-human-verbatim.txt", "HUMAN", "FIT", "已发表论文摘要逐字摘录，20 组作者，取自仓库内维普快照"},
            {"H3", "tests/corpus/cnki-cross.txt", "HUMAN", "FIT", "知网检索页摘要预览截断片段，15 组"},
            {"X1", "tests/corpus/aigc-label-human-polish-agent.txt", "HUMAN", "HOLD", "真人原文 + 代理轻度润色（边界档，算在真人侧，不参与拟合）"},
            {"M-RAW", "tests/corpus/aigc-label-machine-raw.txt", "MACHINE", "FIT", "代理（LLM）生成，未经任何编辑"},
            {"M-EVADE", "tests/corpus/aigc-label-machine-evasive.txt", "MACHINE", "HOLD", "代理（LLM）生成，提示词要求规避风格（不参与拟合）"},
            {"M-DOMAIN", "tests/corpus/aigc-label-machine-domain.txt", "MACHINE", "HOLD", "代理（LLM）生成，与 H1 同领域同主题逐段配对（不参与拟合）"},
    };
    /** 本轮（2026-10-08）追加的 22 条候选：一条都不许带权重进产品表。 */
    private static final AigcFeatureId[] ROUND_2026_10_08 = {
            AigcFeatureId.OPEN_THEORY, AigcFeatureId.CLOSE_REVEAL, AigcFeatureId.NUMBER_CHAIN,
            AigcFeatureId.PASSIVE_TACKET, AigcFeatureId.QUESTION_STUB, AigcFeatureId.PARA_SUMMARY,
            AigcFeatureId.OPENING_ECHO, AigcFeatureId.COLON_LABEL, AigcFeatureId.LIST_MARK,
            AigcFeatureId.TRIPLE_PARALLEL, AigcFeatureId.SENT_LEN_SYMMETRY, AigcFeatureId.SHAPE_REPEAT,
            AigcFeatureId.EMDASH_OVERUSE, AigcFeatureId.EMDASH_ABSENT, AigcFeatureId.AI_JARGON,
            AigcFeatureId.FILLER_HEDGE, AigcFeatureId.VAGUE_ATTRIBUTION, AigcFeatureId.COPULA_AVOID,
            AigcFeatureId.MARKUP_EMPHASIS, AigcFeatureId.RARE_SHAPE, AigcFeatureId.CONNECTIVE_LEAD,
            AigcFeatureId.REPEAT_SPAN,
    };
    /** 天花板表：拟合侧能过线、留出档一句都过不了的那张（由 tools/aigc-fit.ps1 ceiling 搜出来，这里复算）。 */
    private static final double[][] CEILING = {
            {AigcFeatureId.BURST.ordinal(), 0.40d},
            {AigcFeatureId.OPENING_ECHO.ordinal(), 0.40d},
            {AigcFeatureId.SHAPE_REPEAT.ordinal(), 0.40d},
            {AigcFeatureId.SENT_LEN_SYMMETRY.ordinal(), 0.15d},
            {AigcFeatureId.COPULA_AVOID.ordinal(), 0.05d},
    };
    /** 换号门槛（写死，别在这儿放松；依据见类注释第四条）。 */
    private static final double REQUIRED_HOLDOUT_AUC = 0.750d;
    private static final double ALLOWED_HUMAN_FP_PER_1000 = 2d;
    /** 与 AigcDetector 同门槛，抄成常量是为了让断言读得懂。 */
    private static final double GATE = AigcDetector.SEGMENT_FLAG_GATE;

    /** 一个计分句：总分 + 全部特征的原值（不是命中与否，值才有 AUC 可算）。 */
    private static final class Row {
        double score;
        final double[] value = new double[AigcFeatureId.COUNT];
        int family;
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
            row.family = seg.family;
            AigcFeatureId[] ids = AigcFeatureId.values();
            for (int k = 0; k < ids.length; k++) row.value[k] = AigcFeatures.value(ids[k], seg, stats);
            into.add(row);
        }
    }

    /** 用任意权重给一行重新打分：与 AigcScorer.score 同式（组内取最强、组间相加、混排折扣、上限 1）。 */
    private static double scoreWith(Row row, AigcScorer.Coefficients table) {
        AigcFeatureId[] ids = AigcFeatureId.values();
        double[] best = new double[AigcFeatureId.GROUP_COUNT];
        for (int k = 0; k < ids.length; k++) {
            double v = row.value[k];
            if (v <= 0d) continue;
            int g = AigcFeatureId.group(ids[k]);
            double contribution = (v > 1d ? 1d : v) * table.get(ids[k]);
            if (contribution > best[g]) best[g] = contribution;
        }
        double total = 0d;
        for (int g = 0; g < best.length; g++) total += best[g];
        total *= table.discount();
        return total > 1d ? 1d : (total < 0d ? 0d : total);
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
    private static double[] rescored(ArrayList<Row> rows, AigcScorer.Coefficients table) {
        double[] out = new double[rows.size()];
        for (int i = 0; i < out.length; i++) out[i] = scoreWith(rows.get(i), table);
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
        return v.length == 0 ? 0d : m;
    }
    private static int overGate(double[] v) {
        int n = 0;
        for (int i = 0; i < v.length; i++) if (v[i] >= GATE) n++;
        return n;
    }
    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", Double.valueOf(v));
    }
    private static String fmt2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(v));
    }

    public static void main(String[] args) throws Exception {
        LinkedHashMap<String, ArrayList<Row>> byTier = new LinkedHashMap<String, ArrayList<Row>>();
        ArrayList<Row> human = new ArrayList<Row>(), machine = new ArrayList<Row>();
        ArrayList<Row> fitH = new ArrayList<Row>(), fitM = new ArrayList<Row>();
        ArrayList<Row> holdH = new ArrayList<Row>(), holdM = new ArrayList<Row>();
        for (int t = 0; t < TIERS.length; t++) {
            File f = new File(TIERS[t][1]);
            if (!f.isFile()) throw new AssertionError("标注语料缺失：" + TIERS[t][1]
                    + "（" + TIERS[t][0] + "）。没有带标注的语料就没有审计，这个套件不许静默跳过。");
            ArrayList<Row> rows = new ArrayList<Row>();
            for (String p : paragraphs(f)) scan(p, rows);
            if (rows.isEmpty()) throw new AssertionError(TIERS[t][1] + " 一个计分句都没有，语料被改坏了？");
            byTier.put(TIERS[t][0], rows);
            boolean humanTier = "HUMAN".equals(TIERS[t][2]);
            boolean fit = "FIT".equals(TIERS[t][3]);
            if (humanTier) { human.addAll(rows); if (fit) fitH.addAll(rows); else holdH.addAll(rows); }
            else { machine.addAll(rows); if (fit) fitM.addAll(rows); else holdM.addAll(rows); }
        }
        check(true, "七档带标注语料全部读到了计分句，缺一个文件就直接失败："
                + human.size() + " 个人句 / " + machine.size() + " 个机器句"
                + "（拟合侧 " + fitH.size() + " 人 / " + fitM.size() + " 机，留出侧 " + holdH.size()
                + " 人 / " + holdM.size() + " 机；留出侧一律不参与拟合）");
        check("v1-order-only".equals(AigcScorer.VERSION),
                "系数版本还是 v1-order-only：2026-10-08 把判据从 11 条扩到 33 条重新拟合之后，"
                        + "留出档 AUC(机器>真人) 的天花板仍然只有 0.657 < " + fmt(REQUIRED_HOLDOUT_AUC)
                        + "，不够格换 cal-*（换号判据见 AigcScorer#calibrated 与本文件类注释）");

        System.out.println("tier\tsentences\tmean\tp50\tmax\t侧别\t角色\t样本来源");
        double humanMax = 0d, machineMax = 0d;
        for (int t = 0; t < TIERS.length; t++) {
            ArrayList<Row> rows = byTier.get(TIERS[t][0]);
            double[] v = scoresOf(rows);
            java.util.Arrays.sort(v);
            double sum = 0d;
            for (int i = 0; i < v.length; i++) sum += v[i];
            double max = v[v.length - 1];
            if ("HUMAN".equals(TIERS[t][2])) humanMax = Math.max(humanMax, max);
            else machineMax = Math.max(machineMax, max);
            System.out.println(TIERS[t][0] + "\t" + v.length + "\t" + fmt(sum / v.length) + "\t"
                    + fmt(v[v.length / 2]) + "\t" + fmt(max) + "\t" + TIERS[t][2] + "\t" + TIERS[t][3]
                    + "\t" + TIERS[t][4]);
        }

        double aucMachineOverHuman = auc(scoresOf(machine), scoresOf(human));
        double aucFit = auc(scoresOf(fitM), scoresOf(fitH));
        double aucHold = auc(scoresOf(holdM), scoresOf(holdH));
        int humanFalsePositives = overGate(scoresOf(human));
        double fpPer1000 = 1000d * humanFalsePositives / human.size();

        // 逐特征方向表：均值与中位数给"两侧各排多高"，AUC 给"逐句比谁赢"，方向一栏是这两者的结论。
        System.out.println("feature\tgroup\t池\t真人均值\t机器均值\t真人中位\t机器中位\tAUC(机器>真人)\t真人触发率\t机器触发率\t方向\t系数");
        AigcFeatureId[] ids = AigcFeatureId.values();
        AigcScorer.Coefficients base = AigcScorer.defaults(AigcFamily.CHINESE);
        int flipped = 0, dead = 0;
        StringBuilder verified = new StringBuilder(), deadNames = new StringBuilder(), flippedNames = new StringBuilder();
        for (int k = 0; k < ids.length; k++) {
            double[] h = valuesOf(human, k), m = valuesOf(machine, k);
            double a = auc(m, h);
            double fireH = fires(h), fireM = fires(m);
            if (a < 0.45d) {
                flipped++;
                if (flippedNames.length() > 0) flippedNames.append('+');
                flippedNames.append(ids[k].name());
            }
            if (fireH + fireM <= 0d) {
                dead++;
                if (deadNames.length() > 0) deadNames.append('+');
                deadNames.append(ids[k].name());
            }
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
            for (int pool = 0; pool < 2; pool++) {
                ArrayList<Row> ph = pool == 0 ? fitH : holdH, pm = pool == 0 ? fitM : holdM;
                if (ph.isEmpty() || pm.isEmpty()) continue;
                System.out.println(ids[k] + "\t" + AigcFeatureId.groupLabel(ids[k]) + "\t"
                        + (pool == 0 ? "FIT" : "HOLD") + "\t" + fmt(mean(valuesOf(ph, k))) + "\t"
                        + fmt(mean(valuesOf(pm, k))) + "\t" + fmt(median(valuesOf(ph, k))) + "\t"
                        + fmt(median(valuesOf(pm, k))) + "\t" + fmt(auc(valuesOf(pm, k), valuesOf(ph, k)))
                        + "\t" + fmt(fires(valuesOf(ph, k))) + "\t" + fmt(fires(valuesOf(pm, k)))
                        + "\t" + sign + "\t" + fmt2(base.get(ids[k])));
            }
        }
        check(aucMachineOverHuman <= 0.45d,
                "总分方向仍然是反的：AUC(机器>真人)=" + fmt(aucMachineOverHuman) + "（真人 " + human.size()
                        + " 句 / 机器 " + machine.size() + " 句；拟合侧 " + fmt(aucFit) + "，留出侧 " + fmt(aucHold)
                        + "）。判据从 11 条扩到 33 条没有把方向扳回来——谁把它推到 0.55 以上，"
                        + "就来把这里和 docs/aigc-corpus.md 第九节一起改掉");
        check(flipped >= 3,
                "至少三条特征单独看也是反的（实测 " + flipped + " 条 / " + ids.length + " 条 AUC < 0.45："
                        + flippedNames + "）：反不是某一科的偶然，是这套特征的整体取向问题");
        check(humanMax >= GATE,
                "真人侧最高句分 " + fmt(humanMax) + " 已经越过门槛 " + fmt(GATE) + "（每千句误报 "
                        + fmt2(fpPer1000) + " 句 = " + humanFalsePositives + "/" + human.size()
                        + "）：现判据会在真人稿上误报，这条不许被忘掉");
        check(machineMax < GATE,
                "机器侧最高句分 " + fmt(machineMax) + " 连门槛都没碰到：" + fmt(GATE)
                        + " 门槛下这批机器稿一枪没打（含与真人同领域的 M-DOMAIN 在内，机器句共 " + machine.size() + " 句）");
        check(dead <= 10,
                "两侧都不触发的死特征实测 " + dead + " 条 / " + ids.length + " 条 [" + deadNames
                        + "]：审计实际覆盖 " + (ids.length - dead) + " 条。其中 7 条是本轮新增——这批语料是"
                        + "学术段落体，没有列表、没有 markdown，机器稿也没用'赋能/抓手/闭环'那套词，"
                        + "所以它们没有语料可审；谁要配权重，先补能触发它们的语料");
        // 方向为正的特征只有零星几条，撑不起一个"可疑句排序"。这条断言就是"宁可拒绝给数，也不给排序"的依据：
        // 谁把够四分之一推成正了，这里会炸，那时才轮到把报告里的拒绝换成相对排序（docs/aigc-corpus.md 第九节）。
        check(verified.length() == 0 || verifiedChars(verified.toString()) * 4 < ids.length,
                "方向经带标注语料验证为正（AUC >= 0.55 且两侧都有触发）的特征实测 [" + verified
                        + "] = " + verifiedChars(verified.toString()) + " 条 / " + ids.length + " 条，"
                        + "不足四分之一，够不上对外给一个可疑句排序（本轮新增 22 条里有 3 条进来："
                        + "SHAPE_REPEAT 0.763、SENT_LEN_SYMMETRY 0.588、OPENING_ECHO 0.577，老特征只有 BURST）");

        // 第二条闸门：本轮新增的 22 条必须一条都不带权重。方向没验正就进权重，是这个套件最先炸的改法。
        StringBuilder nonzero = new StringBuilder();
        for (int i = 0; i < ROUND_2026_10_08.length; i++) {
            if (base.get(ROUND_2026_10_08[i]) > 0d) {
                if (nonzero.length() > 0) nonzero.append('+');
                nonzero.append(ROUND_2026_10_08[i].name()).append('=').append(base.get(ROUND_2026_10_08[i]));
            }
        }
        check(nonzero.length() == 0,
                "2026-10-08 新增的 " + ROUND_2026_10_08.length + " 条候选在 " + base.version()
                        + " 里全是 0 系数（实测非零的：[" + nonzero + "]）：证据能进报告的依据清单，"
                        + "但不进句分、不进档位、不出数。要配权重，先满足类注释里那两条换号判据");
        boolean listIsTail = ROUND_2026_10_08.length == AigcFeatureId.COUNT - 11;
        for (int i = 0; i < ROUND_2026_10_08.length && listIsTail; i++) {
            if (ROUND_2026_10_08[i].ordinal() != 11 + i) listIsTail = false;
        }
        check(listIsTail,
                "本轮新增特征就是登记表尾部追加的 " + ROUND_2026_10_08.length + " 条（11 条老特征 + "
                        + ROUND_2026_10_08.length + " 条 = " + AigcFeatureId.COUNT
                        + " 条），顺序与 id 顺序一致：加特征必须同时改这里，别让'扩了池'这件事悄悄发生");
        // 拉丁族永远不许跟着中文族升级：版本号前缀写死，见 AigcScorer.LATIN_PREFIX。
        check(!AigcScorer.defaults(AigcFamily.LATIN).version().startsWith("cal-")
                        && AigcScorer.defaults(AigcFamily.LATIN).version().contains("latin"),
                "拉丁族的版本号是 " + AigcScorer.defaults(AigcFamily.LATIN).version()
                        + "：不以 cal- 开头（没有一条英文标注语料，结构上就不许跟着中文族一起'已标定'）");

        // 第三条闸门：'量不到'必须是量出来的，不是门槛选高了。用 AigcScorer.check 允许的天花板表复算一遍。
        AigcScorer.Coefficients ceiling = new AigcScorer.Coefficients(AigcFamily.CHINESE, 1.00d, "ceiling-recheck");
        for (int i = 0; i < CEILING.length; i++) {
            ceiling.set(ids[(int) CEILING[i][0]], CEILING[i][1]);
        }
        double ceilingMachineMax = maxOf(rescored(machine, ceiling));
        double ceilingHumanMax = maxOf(rescored(human, ceiling));
        double holdMachineCeiling = maxOf(rescored(holdM, ceiling));
        int holdHumanOver = overGate(rescored(holdH, ceiling));
        int holdMachineOver = overGate(rescored(holdM, ceiling));
        System.out.println("CEILING 天花板表（BURST/OPENING_ECHO/SHAPE_REPEAT 0.40 + SENT_LEN_SYMMETRY 0.15"
                + " + COPULA_AVOID 0.05，单条 < " + fmt2(GATE) + "、组加和 0.85 <= 1.0，过 AigcScorer.check）"
                + " 机器侧最高分=" + fmt(ceilingMachineMax) + " 真人侧最高分=" + fmt(ceilingHumanMax)
                + " 留出档机器过线=" + holdMachineOver + "/" + holdM.size()
                + " 留出档真人过线=" + holdHumanOver + "/" + holdH.size());
        check(holdMachineOver == 0 && holdHumanOver == 0 && holdMachineCeiling < GATE,
                "静态纪律之内最好的一张表（拟合侧 29/158 机器句过线、真人 0/440）到了留出档是 "
                        + holdMachineOver + "/" + holdM.size() + " 机器过线、留出真人 " + holdHumanOver
                        + "/" + holdH.size() + " 过线，机器侧最高只有 " + fmt(holdMachineCeiling)
                        + " < 门槛 " + fmt(GATE) + "：所以'量不到'是分数根本上不去，不是门槛定高了——"
                        + "把门槛降到 0.35 只会把真人那句 " + fmt(humanMax) + " 放进来");
        check(auc(rescored(holdM, ceiling), rescored(holdH, ceiling)) < REQUIRED_HOLDOUT_AUC,
                "同一张天花板表在留出档的 AUC(机器>真人)="
                        + fmt(auc(rescored(holdM, ceiling), rescored(holdH, ceiling)))
                        + " < " + fmt(REQUIRED_HOLDOUT_AUC) + "：换号判据第一条没过，所以版本号只能是 "
                        + AigcScorer.VERSION + "（真人误报 " + fmt2(fpPer1000) + " 句/千句 ≤ "
                        + fmt2(ALLOWED_HUMAN_FP_PER_1000) + " 单独过了也没用，没有机器句过线就没数可给）");

        // 第四条闸门的文案侧：UNCALIBRATED_NOTE 里那几个数必须就是这个套件现场算出来的数，不许各说各话。
        check(AigcScorer.UNCALIBRATED_NOTE.contains("AUC(机器>真人)=" + fmt(aucMachineOverHuman))
                        && AigcScorer.UNCALIBRATED_NOTE.contains("最高句分 " + fmt(humanMax))
                        && AigcScorer.UNCALIBRATED_NOTE.contains("最高 " + fmt(machineMax))
                        && AigcScorer.UNCALIBRATED_NOTE.contains("每千句误报 " + fmt2(fpPer1000) + " 句"),
                "对外的认错文案里的数是现场量出来的（AUC " + fmt(aucMachineOverHuman) + "、真人最高 "
                        + fmt(humanMax) + "、机器最高 " + fmt(machineMax) + "、误报 " + fmt2(fpPer1000)
                        + "/千句），改语料不改文案会在这里炸：" + AigcScorer.UNCALIBRATED_NOTE);

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
                + "AUC(machine>human)=" + fmt(aucMachineOverHuman) + ", holdout " + fmt(aucHold)
                + ", human-max " + fmt(humanMax) + " vs gate " + fmt(GATE) + ", machine-max " + fmt(machineMax)
                + ", human FP " + fmt2(fpPer1000) + "/1000; 33 features audited, the 22 added on 2026-10-08 "
                + "all at zero weight; verdict: direction still inverted, coefficients stay v1-order-only).");
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
        return copy.length % 2 == 1 ? copy[copy.length / 2] : (copy[copy.length / 2 - 1] + copy[copy.length / 2]) / 2d;
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
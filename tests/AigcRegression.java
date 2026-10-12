package com.rikkahub.wordlite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;

/**
 * AigcDetector 回归：系数生效、每条特征能单独算、人写与模板化文本的相对得分、区间聚合的恒等式、
 * 置信档位的双门槛、样本不足口径、短句门槛与偏移正确性。
 *
 * 0.7.0 改系数时按"序留住、值跟着实测重算、断言文案里带实测数"的规矩动过 9 条数值断言，
 * 每条旁边都注明了原值、新值与为什么安全；实测数出自 tools/aigc-calibration.ps1
 * （产物 artifacts/corpus/aigc-sweep.txt，读数见 docs/aigc-calibration.md）。
 */
public final class AigcRegression {
    private static int count;

    private static final ArrayList<String> failures = new ArrayList<String>();

    /**
     * 一条没过就记一条、继续往下跑：口径换了的时候要看清"哪些跟着变了"，不是看见第一条就停。
     * 记账不改严肃性——只要有一条没跑通，{@link #main} 最后还是抛，套件照样红。
     */
    private static void check(boolean ok, String message) {
        if (!ok) {
            failures.add(message);
            System.out.println("FAIL " + message);
            return;
        }
        count++;
        System.out.println("PASS " + message);
    }

    /** 人写风格：长短句混排、具体细节、几乎没有模板句。实测 rate 4.35（0.5.4 是 5.5 上下）。 */
    private static final String HUMAN = "我们把三台标注机搬到走廊尽头，插座不够，最后拉了一卷线板。"
            + "第一天就出问题：录音里混进空调的嗡声，十一份样本得重录。"
            + "第二天换了麦克风位置，噪声是小了，可有个受访者的方言太重，转写软件整段听不懂，只好请本地一位老师做听写，一天三百块。"
            + "小王起初不同意，觉得太贵，预算本来就紧。"
            + "第三天下午忽然来了十七个学生，队伍排到楼梯口，那天反而录得最多。"
            + "晚上回宿舍对时间码，才发现有一段少了两分钟，谁也想不起是哪儿断的。";

    /** 有一点公文腔，但没有成套模板。实测 rate 13.51。 */
    private static final String MILD = "通过对实验数据的分析，可以发现该方法在噪声环境下表现较好。"
            + "实验在四个公开数据集上进行，每个数据集随机划分八二分。"
            + "我们手工检查了其中两百条错例，多数是标点引起的分句错误。"
            + "综上，方法有效，但仍需在更大规模的数据上验证。";

    /** 模板化 LLM 腔：排比、连接词、评价词、句首结构复读。实测 rate 27.64（组内取最强之后比 0.5.4 低一截）。 */
    private static final String AI = "综上所述，本研究通过对实验数据的全面分析，可以发现该方法在多个维度上都表现出显著优势。"
            + "首先，通过深入优化训练策略，可以显著提升模型的收敛速度。"
            + "其次，通过引入注意力机制，可以有效降低人工标注的成本。"
            + "再次，通过构建统一的评价体系，可以全面提高评估结果的稳定性。"
            + "值得注意的是，数据规模的扩大不仅提升了模型性能，还为后续研究提供了坚实的基础。"
            + "此外，该框架具有广阔的应用前景和重要的理论价值。";

    /** AI 的第一句，多处断言拿它当尺子，提成常量免得抄两遍。 */
    private static final String AI_SENTENCE_1 = "综上所述，本研究通过对实验数据的全面分析，可以发现该方法在多个维度上都表现出显著优势。";

    /** 满档机器腔：每句四条套话 + 排比 + 评价词，用来把档位推到复核档以上。实测单句 0.59 上下。 */
    private static final String HEAVY = "综上所述，通过上述分析可以发现，这套流程的稳定性还有提升空间，为后续优化提供了重要的参考，也具有显著的现实意义和可操作的改进方向。"
            + "首先，通过统一的评价体系，可以全面提高结果的可靠性，不仅降低了人工成本，而且为同类项目提供了有益的借鉴和可复用的模板。"
            + "其次，通过持续优化交付流程，可以有效压缩交付周期，为团队积累了扎实的经验，也为下一期奠定了良好的基础。"
            + "值得注意的是，随着技术的不断演进，该方案不仅具有突出的理论价值，还为推广应用提供了广阔的空间和充分的落地条件。";

    private static final String AI_EN = "Moreover, it is worth noting that the proposed method plays a crucial role "
            + "in long document understanding. Furthermore, in addition to the accuracy gains, the framework "
            + "reduces annotation cost. In conclusion, the extensive experiments provide valuable insights "
            + "into the behaviour of attention modules.";

    private static final String HUMAN_EN = "We ran the model overnight on a single GPU and it choked halfway through. "
            + "The first 400 steps looked fine. Then the loss went flat, and by morning it had not moved at all; "
            + "we later found a stale checkpoint in the loader. Restarting from scratch cost us two days.";

    private static final String FULLWIDTH_EN = "ｉｔ ｉｓ ｗｏｒｔｈ ｎｏｔｉｎｇ ｔｈａｔ ｔｈｉｓ ａｐｐｒｏａｃｈ "
            + "ｐｌａｙｓ ａ ｃｒｕｃｉａｌ ｒｏｌｅ ｉｎ ｄｏｃｕｍｅｎｔ ｕｎｄｅｒｓｔａｎｄｉｎｇ． "
            + "ＭＯＲＥＯＶＥＲ， ｉｔ ｐｒｏｖｉｄｅｓ ｖａｌｕａｂｌｅ ｉｎｓｉｇｈｔｓ ｆｏｒ ｆｕｔｕｒｅ ｗｏｒｋ．";

    /**
     * 真实论文正文里"最像机写的真人句子"（钉子户清单，实测当前系数下最高 0.366，门槛 0.45，余量 +0.084）。
     * 同一批句子在 tests/corpus/aigc-hard-human.txt 里，标定台的 NAIL 行读那份文件；这里留一份是为了
     * 让本套件不依赖工作目录也能跑。
     */
    private static final String[] HARD_HUMAN = {
        "通过调控多孔铜的孔隙率、孔径、孔壁厚度和孔道连通性，建立多孔铜结构参数与其导电、导热、力学及润湿性能之间的关系，为多孔铜中间层的可控制备提供基础。",
        "该研究说明，多孔Cu参与TLP连接时，孔道填充、界面反应和压力控制相互关联，为后续设计多孔Cu中间层及确定连接参数提供了参考。",
        "因此，如何在缩短连接时间的同时，控制金属间化合物的生成，并改善接头的应力承载能力，成为瞬态液相连接技术进一步应用需要解决的问题。",
    };

    public static void main(String[] args) throws Exception {
        installTables();
        instrument();
        parity();
        guards();
        corpusTransfer();
        ordering();
        templates();
        shortSentences();
        offsets();
        metrics();
        profiles();
        edgeCases();
        performance();
        coefficients();
        features();
        segments();
        tiers();
        hardHuman();
        System.out.println("SUMMARY " + count + " assertions passed, " + failures.size()
                + " failed (段分是一条相对刻度，不是权威判决).");
        if (!failures.isEmpty())
            throw new AssertionError(failures.size() + " 条断言没过，第一条：" + failures.get(0));
    }

    /* ================= 语料与统计工具 ================= */

    /** 带标注的真人侧语料（正文 + 逐字摘录 + 真人润色）。 */
    private static final String[] HUMAN_FILES = {"real-prose.txt", "aigc-label-human-verbatim.txt",
            "aigc-label-human-polish-agent.txt"};
    /** 带标注的机器侧语料（整节直出 / 刻意避套话 / 领域对齐）。 */
    private static final String[] MACHINE_FILES = {"aigc-label-machine-raw.txt",
            "aigc-label-machine-evasive.txt", "aigc-label-machine-domain.txt"};

    /** 一份"没有一段过线"的真人文档：真人正文里取够判定规模、且没过线的段落拼起来。 */
    private static String cleanHumanDoc() {
        StringBuilder out = new StringBuilder();
        for (String text : corpus("real-prose.txt")) {
            AigcRhythm.Reading reading = AigcRhythm.measure(text, 0, text.length());
            if (reading.suspicious || reading.validChars < 200) continue;
            if (out.length() > 0) out.append('\n');
            out.append(text);
            if (out.length() >= 900) break;
        }
        return out.toString();
    }

    /** 一批语料里所有"算得出节奏"的段落分。 */
    private static ArrayList<Double> paragraphScores(String[] files) {
        ArrayList<Double> out = new ArrayList<Double>();
        for (int i = 0; i < files.length; i++) {
            ArrayList<String> lines = corpus(files[i]);
            for (int k = 0; k < lines.size(); k++) {
                String text = lines.get(k);
                AigcRhythm.Reading reading = AigcRhythm.measure(text, 0, text.length());
                if (reading.spoke) out.add(Double.valueOf(reading.score));
            }
        }
        return out;
    }

    /** 达到判定规模（{@link AigcRhythm#flagChars()} 字且 {@link AigcRhythm#flagSentences()} 句）的段落计数。 */
    private static int[] countFlagged(String[] files) {
        int eligible = 0, flagged = 0;
        for (int i = 0; i < files.length; i++) {
            ArrayList<String> lines = corpus(files[i]);
            for (int k = 0; k < lines.size(); k++) {
                String text = lines.get(k);
                AigcRhythm.Reading reading = AigcRhythm.measure(text, 0, text.length());
                if (reading.validChars < AigcRhythm.flagChars()
                        || reading.sentences < AigcRhythm.flagSentences()) continue;
                eligible++;
                if (reading.suspicious) flagged++;
            }
        }
        return new int[]{eligible, flagged};
    }

    private static double median(ArrayList<Double> values) {
        ArrayList<Double> sorted = new ArrayList<Double>(values);
        java.util.Collections.sort(sorted);
        return sorted.isEmpty() ? Double.NaN : sorted.get(sorted.size() / 2).doubleValue();
    }

    /** 段级 AUC（正例 = 机器段）：随机拿一段机器与一段真人比，机器更高的概率。 */
    private static double auc(ArrayList<Double> positive, ArrayList<Double> negative) {
        double wins = 0d, ties = 0d;
        for (int i = 0; i < positive.size(); i++)
            for (int k = 0; k < negative.size(); k++) {
                double p = positive.get(i).doubleValue(), n = negative.get(k).doubleValue();
                if (p > n) wins++;
                else if (p == n) ties++;
            }
        return positive.isEmpty() || negative.isEmpty() ? Double.NaN
                : (wins + 0.5d * ties) / (positive.size() * (double) negative.size());
    }

    /**
     * verdict 是"结论句 + 出处行"拼起来的，出处行永远挂在末尾。要单独审"指数那一句"里
     * 到底印了什么（置信档该不该出现在那儿、有没有混进百分比），先把出处行切掉。
     */
    private static String indexSentence(AigcDetector.Result result) {
        String verdict = result.verdict, provenance = result.provenance;
        if (verdict == null || provenance == null || provenance.length() == 0
                || !verdict.endsWith(provenance)) return verdict == null ? "" : verdict;
        int cut = verdict.length() - provenance.length();
        return cut > 0 && verdict.charAt(cut - 1) == '。' ? verdict.substring(0, cut - 1)
                : verdict.substring(0, cut);
    }

    /** 同一个口径只许说一次：数一个词在一段话里出现几次。 */
    private static int countWord(String text, String needle) {
        if (text == null || needle == null) return 0;
        int n = 0, at = text.indexOf(needle);
        while (at >= 0) {
            n++;
            at = text.indexOf(needle, at + needle.length());
        }
        return n;
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.US, "%.4f", Double.valueOf(value));
    }

    private static String pct(int part, int whole) {
        return whole == 0 ? "没有可比段落"
                : String.format(java.util.Locale.US, "%.1f%%", Double.valueOf(part * 100d / whole));
    }

    /**
     * 装载随包判据表与套话表。分数完全由这两张表决定，读不到就没有任何一条断言有意义，
     * 所以这里直接抛，不许退化成"表没装载也能过"。
     */
    private static void installTables() throws Exception {
        java.io.InputStream table = new java.io.FileInputStream(RHYTHM_TABLE);
        AigcRhythm.Table loaded = AigcRhythm.install(table);
        check(loaded.engaged(), "随包判据表 " + RHYTHM_TABLE + " 装得上、19 条全对上（matched="
                + loaded.matched + "/" + loaded.criteria.size() + "）");
        java.io.InputStream tells = new java.io.FileInputStream(TELLS_TABLE);
        AigcTells.install(tells);
    }

    private static final String RHYTHM_TABLE = "app/src/main/assets/aigc/aigc-rhythm.tsv";
    private static final String TELLS_TABLE = "app/src/main/assets/aigc/ai-tells.tsv";
    private static final String GOLDEN_TABLE = "tests/corpus/aigc-instrument-golden.tsv";

    /**
     * 仪器本体：随包表的条数、真人刻度、出处那一行的每一个字、置信档、判定门槛、"未发现"的说法。
     * 这一节钉的全是界面上要印出来的东西，改一个字都要在这里先红一次。
     */
    private static void instrument() {
        AigcRhythm.Table table = AigcRhythm.current();
        check(AigcRhythm.CRITERIA == 19 && AigcRhythm.RHYTHM == 15,
                "判据 19 条 = 节奏结构 15 条 + 用词统计 4 条（表里 " + table.criteria.size() + " 行）");
        check(table.matched == 19, "19 行的 id 全对得上实现，没有一条被当成不认识而丢掉");
        check("instrument-19-2026-10-12".equals(AigcRhythm.version()),
                "仪器版本号直接取自表头，界面上印的就是它（" + AigcRhythm.version() + "）");
        check(Math.abs(AigcRhythm.humanP50() - 0.4496d) < 1e-9d
                        && Math.abs(AigcRhythm.humanP90() - 2.958d) < 1e-9d
                        && Math.abs(AigcRhythm.humanP99() - 4.5585d) < 1e-9d,
                "真人刻度三把线从表里读：中位 " + AigcRhythm.humanP50() + " · P90 "
                        + AigcRhythm.humanP90() + " · P99 " + AigcRhythm.humanP99());
                check(("instrument-19-2026-10-12 · 定标 2531 对学术配对 · 段级 AUC 0.930 / 整篇 0.982 · "
                        + "划线真人误报 1.11%").equals(AigcRhythm.describe()),
                "出处那一行逐字钉住（实测值 " + AigcRhythm.describe() + "）");
        check(AigcRhythm.confidence() == AigcConfidence.MEDIUM,
                "置信档由这件仪器自己的实测 AUC 与误报率算出来，不是写死的（实测 "
                        + AigcRhythm.confidence() + "）");
        check(AigcRhythm.flagChars() == 120 && AigcRhythm.flagSentences() == 4
                        && AigcRhythm.MIN_FLAG_CHARS == 120 && AigcRhythm.MIN_FLAG_SENTENCES == 4,
                "判可疑的门槛：整段 >=120 有效字且 >=4 句；优先读表头 gate_min_*（表里 "
                        + table.gateChars + "/" + table.gateSentences + "，常量兜底 "
                        + AigcRhythm.MIN_FLAG_CHARS + "/" + AigcRhythm.MIN_FLAG_SENTENCES + "）");
        check("未发现可疑段落".equals(AigcDetector.NOT_FOUND)
                        && AigcDetector.NOT_FOUND.indexOf("通过") < 0
                        && AigcDetector.NOT_FOUND.indexOf("没有") < 0,
                "可疑字数为 0 只能写未发现，不许写成通过或没有（" + AigcDetector.NOT_FOUND + "）");
        check(AigcRhythm.WEAK_UNDETECTED.indexOf("并成一句") > 0
                        && AigcRhythm.WEAK_LAYOUT.indexOf("OCR") > 0,
                "两条软肋各有一句话，界面上跟数一起出现");
        check(AigcRhythm.caveatLine().indexOf("跨学科") >= 0
                        && AigcRhythm.caveatLine().indexOf("刻意模仿") >= 0,
                "没验过什么也写在出处里（" + AigcRhythm.caveatLine() + "）");
    }

    /**
     * Python 与 Java 对不上就炸：黄金样本里每一段、每条判据的取值与段分都逐条比。
     * 黄金样本由 {@code tools/aigc-instrument-golden.py} 用同一张随包表算出来。
     */
    private static void parity() throws Exception {
        java.util.List<String> lines = Files.readAllLines(Paths.get(GOLDEN_TABLE), StandardCharsets.UTF_8);
        String[] header = null;
        int rows = 0, flagged = 0, bad = 0;
        double worst = 0d;
        String worstAt = "";
        for (String line : lines) {
            if (line.length() == 0 || line.startsWith("#")) continue;
            String[] cells = line.split("	", -1);
            if (header == null) {
                header = cells;
                continue;
            }
            rows++;
            String text = cells[cells.length - 1];
            AigcRhythm.Reading reading = AigcRhythm.measure(text, 0, text.length());
            for (int i = 0; i < AigcRhythm.CRITERIA; i++) {
                double want = Double.parseDouble(cells[3 + i]);
                double got = reading.values[i];
                double tolerance = Math.max(1e-6d, Math.abs(want) * 1e-6d);
                if (Math.abs(want - got) > tolerance) {
                    bad++;
                    if (bad <= 5) throw new AssertionError("判据取值对不上：" + cells[0] + " " + header[3 + i]
                            + " Python " + want + " / Java " + got);
                }
            }
            double wantScore = Double.parseDouble(cells[3 + AigcRhythm.CRITERIA]);
            double delta = Math.abs(wantScore - reading.score);
            if (delta > Math.max(1e-6d, Math.abs(wantScore) * 1e-6d)) {
                bad++;
                throw new AssertionError("段分对不上：" + cells[0] + " Python " + wantScore
                        + " / Java " + reading.score);
            }
            if (delta > worst) { worst = delta; worstAt = cells[0]; }
            if (reading.suspicious) flagged++;
        }
        check(rows >= 40 && bad == 0,
                "黄金样本 " + rows + " 段 × " + AigcRhythm.CRITERIA + " 条判据取值与段分逐条对上，"
                        + "Java 与 Python 算的是同一个数（最大偏差 " + worst + "，出现在 " + worstAt + "）");
        check(flagged > 0, "黄金样本里确实有待得住的可疑段（" + flagged + " 段），这条对等不是空转");
    }

    /** 读 tests/corpus 里那批标注语料的自然段（跳过 # 注释行）。 */
    private static ArrayList<String> corpus(String file) {
        ArrayList<String> out = new ArrayList<String>();
        try {
            for (String line : Files.readAllLines(Paths.get("tests/corpus/" + file), StandardCharsets.UTF_8)) {
                if (line.startsWith("#")) continue;
                String text = line.trim();
                if (text.length() > 0) out.add(text);
            }
        } catch (Exception missing) {
            throw new AssertionError("标注语料 " + file + " 读不到，这一节没法判：" + missing);
        }
        return out;
    }

    private static double medianScore(ArrayList<String> paragraphs) {
        ArrayList<Double> scores = new ArrayList<Double>();
        for (int i = 0; i < paragraphs.size(); i++) {
            String text = paragraphs.get(i);
            AigcRhythm.Reading reading = AigcRhythm.measure(text, 0, text.length());
            if (reading.spoke) scores.add(Double.valueOf(reading.score));
        }
        java.util.Collections.sort(scores);
        return scores.isEmpty() ? Double.NaN
                : scores.get(scores.size() / 2).doubleValue();
    }

    private static boolean hasFeature(AigcDetector.Result result, String needle) {
        for (int i = 0; i < result.sentences.size(); i++) {
            ArrayList<String> features = result.sentences.get(i).features;
            for (int k = 0; k < features.size(); k++) {
                if (features.get(k).indexOf(needle) >= 0) return true;
            }
        }
        return false;
    }

    private static boolean anyTemplate(AigcDetector.Result result) {
        return hasFeature(result, "模板句式：");
    }

    /** 一句话切成打分单元。 */
    private static AigcFeatures.Segment segment(String text) {
        String norm = TextCorpus.normalize(text);
        ArrayList<int[]> spans = TextCorpus.sentences(text);
        if (spans.isEmpty()) return AigcFeatures.segment(norm, 0, norm.length());
        int[] span = spans.get(0);
        return AigcFeatures.segment(norm, span[0], span[1]);
    }

    /** 一段话的文档级统计（切句 + 过字数门槛 + 句长变异系数与句首众数）。 */
    private static AigcFeatures.DocStats statsOf(String text) {
        String norm = TextCorpus.normalize(text);
        ArrayList<int[]> spans = TextCorpus.sentences(text);
        ArrayList<AigcFeatures.Segment> all = AigcFeatures.segments(norm, spans);
        ArrayList<AigcFeatures.Segment> scored = new ArrayList<AigcFeatures.Segment>();
        for (int i = 0; i < all.size(); i++) if (AigcFeatures.scores(all.get(i))) scored.add(all.get(i));
        return AigcFeatures.stats(norm, spans, scored);
    }

    /**
     * 绝对分数线的跨语域迁移实测。界面与报告的出处行里那句「搬到另一批真人语料上同一条线误报多少」，
     * 数字就是这里现场量的：口径与出厂判定完全一致（段分过真人 P99，且整段不少于 120 有效字、不少于 4 句）。
     *
     * <p>为什么要有这一节：定标库那条线（真人段落分 P99）划在 DetectRL-X 中文学术正文上，误报 1.11%。
     * 同一条线搬到仓库这批标注语料上，真人侧过线率升到一成五上下——排序照旧分得开（AUC 0.995），
     * 绝对分却不跨语料通用。这一句必须跟着数一起出现在界面上，否则「误报 1.11%」会被读成
     * 「什么稿子都只误报 1.11%」。
     */
    private static void corpusTransfer() {
        int[] human = countFlagged(HUMAN_FILES);
        int[] machine = countFlagged(MACHINE_FILES);
        check(human[0] == AigcRhythm.MEASURED_CROSS_HUMAN_SEGMENTS
                        && human[1] == AigcRhythm.MEASURED_CROSS_HUMAN_FLAGGED,
                "真人侧够判定规模的 " + human[0] + " 段里过线 " + human[1] + " 段（" + pct(human[1], human[0])
                        + "）——出处里那句跨语域误报就是它");
        check(machine[0] == AigcRhythm.MEASURED_CROSS_MACHINE_SEGMENTS && machine[1] == machine[0],
                "同一批语料里机器侧够判定的 " + machine[0] + " 段全过线（" + pct(machine[1], machine[0]) + "）");
        check(AigcRhythm.OUT_OF_DOMAIN.indexOf(String.valueOf(human[0])) > 0
                        && AigcRhythm.OUT_OF_DOMAIN.indexOf(String.valueOf(human[1])) > 0,
                "界面上那句跨语域提示里的两个数就是本节量出来的，不是抄下来的死数："
                        + AigcRhythm.OUT_OF_DOMAIN);
        int[] abstracts = countFlagged(new String[]{"aigc-label-human-verbatim.txt"});
        check(abstracts[0] > 0 && abstracts[1] > 0,
                "真人摘要那一档单独看更差：" + abstracts[0] + " 段里过线 " + abstracts[1] + " 段（"
                        + pct(abstracts[1], abstracts[0]) + "）；摘要与节选本来就不在这件仪器的定标语域里");
    }

    /** 区间字数应当等于成员句有效字符之和。 */
    private static int expectedChars(AigcDetector.Result run, AigcDetector.Segment seg, String norm) {
        int chars = 0;
        for (int i = seg.fromSentence; i <= seg.toSentence; i++) {
            AigcDetector.Sentence member = run.sentences.get(i);
            chars += TextCorpus.validCount(norm, member.start, member.end);
        }
        return chars;
    }
    /** 样本门槛与引用排除：不给小样本编造比例，也不拿抄来的句子判机器腔。 */
    private static void guards() {
        check(AigcDetector.MIN_DOCUMENT_CHARS == 400, "样本门槛定在 400 个有效字符");
        AigcDetector.Result shortRun = AigcDetector.detect(HUMAN);
        check(shortRun.insufficientSample, "几百字的人写段落被判为样本不足");
        // 2026-10-12 起分数出自随包判据表（docs/aigc-rhythm.md）。"判据未标定"盖住整格的做法退场：
        // 仪器自带标定与误报率，量得到就照印；量不到的三种场合（样本不足 / 表没装载 / 段落凑不出节奏）
        // 各说一句人话。这里验的是"量不到时只说量不到的那一个原因"。
        check(AigcDetector.measurement(shortRun).indexOf("样本不足") == 0
                        && shortRun.verdict.indexOf("样本不足") == 0
                        && shortRun.verdict.indexOf("AI 倾向指数") < 0,
                "样本不足那一轮只说样本不足，指数与可疑字数都不出口（" + shortRun.verdict + "）");
        check(shortRun.sentences.size() > 0, "样本不足照样列出特征句供人看");
        // 段级口径下同一段落重复三遍会把用词统计算歪，长样例一律用换行分成三段（与真论文一样一段一行）。
        String longish = AI + "\n" + AI + "\n" + AI;
        AigcDetector.Result longRun = AigcDetector.detect(longish);
        check(!longRun.insufficientSample, "过了 400 字门槛才给指数（实测 " + longRun.comparedChars + " 字）");
        // 可疑字数＝过线段落的有效字数之和（不是计分句字数），522 是这一轮的实测。
        check(longRun.rate > AigcRhythm.humanP99() && longRun.flaggedChars == 522
                        && longRun.verdict.indexOf("AI 倾向指数") > 0
                        && longRun.verdict.indexOf("不是判决") > 0,
                "过了门槛就同时给指数与可疑字数，出处末尾那句不是判决跟着一起出口（指数 "
                        + longRun.rate + " 高于真人 P99 " + AigcRhythm.humanP99() + "，可疑 "
                        + longRun.flaggedChars + " 字）");
        AigcDetector.Result plain = AigcDetector.detect(cleanHumanDoc());
        check(plain.flaggedChars == 0 && plain.tier.ordinal() <= AigcDetector.Tier.WATCH.ordinal()
                        && AigcDetector.flaggedLine(plain).indexOf(AigcDetector.NOT_FOUND) == 0
                        && AigcDetector.flaggedLine(plain).indexOf("不等于没有机器生成") > 0,
                "真人正文没有一段过线时那一格写未发现，紧跟一句这不等于没有机器生成（"
                        + AigcDetector.flaggedLine(plain) + "）");
        String indexLine = indexSentence(plain);
        check(indexLine.indexOf("真人论文正文：中位") > 0 && indexLine.indexOf("置信档") < 0
                        && countWord(plain.provenance, "置信档") == 1
                        && AigcDetector.measurement(plain).indexOf("置信档") < 0,
                "指数后面并排印的是真人三把刻度；置信档只在出处那一行说一次（指数那一句："
                        + indexLine + "）");
        check(AigcDetector.detect(AI).rate == AigcDetector.detect(AI, null).rate,
                "不传排除区间与传 null 结果一致");
        String mixed = AI + MILD + MILD + MILD;
        int aiEnd = AI.length();
        AigcDetector.Result full = AigcDetector.detect(mixed);
        AigcDetector.Result quoted = AigcDetector.detect(mixed, new int[]{0, aiEnd});
        check(quoted.excludedChars > 0, "被圈住的字数记在报告里");
        check(quoted.sentences.size() < full.sentences.size(), "引用区间里的句子不再打分");
        check(quoted.rate < full.rate, "把模板句圈成引用之后整篇指数下降（" + quoted.rate + " < " + full.rate + "）");
        boolean insideExcluded = false;
        for (int i = 0; i < quoted.sentences.size(); i++)
            if (quoted.sentences.get(i).start < aiEnd) insideExcluded = true;
        check(!insideExcluded, "排除区间里没有残留计分句");
    }

    /**
     * 序：真人段落低于机器段落。量在 tests/corpus 那批带标注的语料上。
     *
     * <p>旧写法拿三条自制样例（人写 / 半公文 / 模板腔）当尺子，钉的是 0~100 那个百分比。段分换成
     * 段级 z 加权和之后那把尺不存在了：几十个字的一段算不出句长分布，样例之间的分差说明不了任何事
     * （实测自制人写样例 7.57 分反而高于自制模板腔整篇 2.08 分，就是小样本把用词统计顶起来的）。
     * 所以现在只在成批的标注语料上量序。
     */
    private static void ordering() {
        ArrayList<Double> human = paragraphScores(HUMAN_FILES);
        ArrayList<Double> machine = paragraphScores(MACHINE_FILES);
        check(human.size() >= 60 && machine.size() >= 40,
                "标注语料里算得出节奏的段落：真人 " + human.size() + " 段、机器 " + machine.size() + " 段");
        double humanMedian = median(human), machineMedian = median(machine);
        check(machineMedian > humanMedian + 2d,
                "机器段落中位分明显高于真人段落（" + fmt(machineMedian) + " 对 " + fmt(humanMedian) + "）");
        double rank = auc(machine, human);
        check(rank > 0.95d, "同一批语料上的段级 AUC（机器高于真人）= " + fmt(rank) + "，排序这一层站得住");
        check(Math.abs(rank - AigcRhythm.MEASURED_CROSS_AUC) < 0.005d,
                "出处里那句跨语域 AUC 与本节实测对得上（写 " + fmt(AigcRhythm.MEASURED_CROSS_AUC)
                        + "，量到 " + fmt(rank) + "）");
        AigcDetector.Result latin = AigcDetector.detect(HUMAN_EN + "\n" + AI_EN + "\n" + HUMAN_EN);
        check(!AigcDetector.indexable(latin)
                        && AigcDetector.measurement(latin).indexOf("计分正文以英文为主") == 0
                        && AigcDetector.measurement(latin).indexOf("不在它的定标范围内") > 0
                        && AigcDetector.measurement(latin).indexOf("不等于没有机器腔") > 0,
                "整篇英文给的是「不在定标范围内」那一句，而不是一个偏低的指数（"
                        + AigcDetector.measurement(latin) + "）");
    }

    private static float topScore(AigcDetector.Result result) {
        float top = 0f;
        for (int i = 0; i < result.sentences.size(); i++)
            if (result.sentences.get(i).score > top) top = result.sentences.get(i).score;
        return top;
    }

    private static boolean rate(AigcDetector.Result result) {
        return result.rate >= 0f && result.rate <= 100f;
    }

    private static void templates() {
        AigcDetector.Result ai = AigcDetector.detect(AI);
        check(hasFeature(ai, "模板句式：综上所述"), "综上所述 shows up as a named template hit");
        check(hasFeature(ai, "模板句式：值得注意的是"), "值得注意的是 shows up as a named template hit");
        check(hasFeature(ai, "模板句式：为……提供了"), "为……提供了 shows up as a named template hit");
        check(hasFeature(ai, "连接词密度偏高"), "connective density is reported by name");
        check(hasFeature(ai, "句长突发度低"), "flat sentence-length burst is reported by name");
        check(hasFeature(ai, "程度副词/评价词堆叠"), "stacked intensifiers are reported by name");
        check(!anyTemplate(AigcDetector.detect(HUMAN)), "the human paragraph triggers no template at all");
        String parallel = "首先我们需要清洗原始语料，其次要补齐缺失的字段，最后再把结果整理成一张对照表。";
        AigcDetector.Result frame = AigcDetector.detect(parallel);
        check(hasFeature(frame, "模板句式：首先……其次……最后"), "the 首先/其次/最后 frame is caught inside one sentence");
        AigcDetector.Result english = AigcDetector.detect(AI_EN);
        check(hasFeature(english, "模板句式：it is worth noting"), "English boilerplate opens the feature list");
        check(hasFeature(english, "moreover/furthermore"), "moreover/furthermore is named");
        check(hasFeature(english, "in conclusion"), "in conclusion is named");
        check(hasFeature(english, "plays a crucial role"), "plays a crucial role is named");
        check(!anyTemplate(AigcDetector.detect(HUMAN_EN)), "ordinary English prose triggers no template");
        AigcDetector.Result folded = AigcDetector.detect(FULLWIDTH_EN);
        check(hasFeature(folded, "it is worth noting") && hasFeature(folded, "moreover/furthermore"),
                "fullwidth Latin boilerplate is folded before the template scan");
        boolean chineseNames = true;
        /* 2026-10-12 起句分出自随包判据表，AigcScorer 那套启发式系数只用来给证据起名字。
           于是"分数大于 0 必带证据 / 0 分不许带权重证据"这两条老不变量不再成立：段分与证据
           是两条各自独立的账。留在这里的纪律是三条：证据名一律带中文；段级判据要带着自己的
           前缀出现在逐句依据里（界面上点开一段看到的就是它）；段分可正可负，不许再拿 0~1 去卡它。 */
        int rhythmEvidence = 0, lexicalEvidence = 0, tellEvidence = 0, positive = 0;
        ArrayList<String> lexicalNames = new ArrayList<String>();
        for (int q = 0; q < AigcRhythm.current().criteria.size(); q++) {
            AigcRhythm.Criterion criterion = AigcRhythm.current().criteria.get(q);
            if (criterion.id.indexOf("L") == 0) lexicalNames.add(criterion.name);
        }
        ArrayList<AigcDetector.Result> all = new ArrayList<AigcDetector.Result>();
        all.add(ai);
        all.add(english);
        all.add(AigcDetector.detect(HUMAN));
        for (int i = 0; i < all.size(); i++) {
            ArrayList<AigcDetector.Sentence> items = all.get(i).sentences;
            for (int k = 0; k < items.size(); k++) {
                AigcDetector.Sentence sentence = items.get(k);
                for (int j = 0; j < sentence.features.size(); j++) {
                    String evidence = sentence.features.get(j);
                    if (!hasCJK(evidence)) chineseNames = false;
                    // 19 条里有四条是用词统计，前缀不能再写死"节奏"，否则新四条在界面上隐身。
                    if (evidence.indexOf("判据：") == 0) {
                        rhythmEvidence++;
                        for (int m = 0; m < lexicalNames.size(); m++)
                            if (evidence.indexOf(lexicalNames.get(m)) > 0) lexicalEvidence++;
                    }
                    if (evidence.indexOf("套话线索：") == 0) tellEvidence++;
                }
                if (sentence.score > 0f) positive++;
            }
        }
        check(chineseNames, "每一条证据名都带中文，复核的人不用翻译");
        check(rhythmEvidence > 0, "判据的名字带着「判据：」前缀进了逐句依据（" + rhythmEvidence
                + " 条），报告里那段的可疑理由就是它");
        check(lexicalNames.size() == 4 && lexicalEvidence > 0,
                "新加的四条用词判据 " + lexicalNames + " 能顶着同一个前缀进逐句依据（实测 "
                        + lexicalEvidence + " 条）——19 条不许只在分数里出力、在界面上隐身");
        check(positive > 0, "样例三段里段分为正的计分句有 " + positive
                + " 个——段分可正可负，0 到 1 那把尺已经不存在了");
        ArrayList<String> abstracts = corpus("aigc-label-human-verbatim.txt");
        StringBuilder abstractDoc = new StringBuilder();
        for (int i = 0; i < abstracts.size() && i < 4; i++) abstractDoc.append(abstracts.get(i)).append("\n");
        check(hasFeature(AigcDetector.detect(abstractDoc.toString()), "套话线索：") || tellEvidence > 0,
                "套话类线索带着「套话线索：」前缀进依据（本批样例实测 " + tellEvidence
                        + " 条），它不计分，所以在依据里必须自己标出来");
    }


    /**
     * 这一段里"系数非零的特征"能产出的证据文案集合。用来判定一条证据是不是只可能来自 0 系数的新特征。
     * 中文族与拉丁族的非零系数集合完全一致（AigcScorer.install 按特征名平移），所以取中文族一张就够。
     */
    private static java.util.HashSet<String> weightedEvidence(String text) {
        java.util.HashSet<String> out = new java.util.HashSet<String>();
        String norm = TextCorpus.normalize(text);
        ArrayList<int[]> spans = TextCorpus.sentences(text);
        ArrayList<AigcFeatures.Segment> all = AigcFeatures.segments(norm, spans);
        AigcFeatures.DocStats stats = statsOf(text);
        AigcScorer.Coefficients co = AigcScorer.current(AigcFamily.CHINESE);
        for (int i = 0; i < all.size(); i++) {
            if (!AigcFeatures.scores(all.get(i))) continue;
            ArrayList<AigcFeatures.Hit> hits = AigcFeatures.of(all.get(i), stats);
            for (int k = 0; k < hits.size(); k++) {
                if (co.get(hits.get(k).id) > 0d) out.addAll(hits.get(k).evidence);
            }
        }
        return out;
    }
    private static boolean hasCJK(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    private static void shortSentences() {
        AigcDetector.Result chatter = AigcDetector.detect("好的。走吧。明天见吧。行。");
        check(chatter.sentences.isEmpty() && chatter.comparedChars == 0 && chatter.rate == 0f,
                "chatter under eight characters is never scored");
        AigcDetector.Result marks = AigcDetector.detect("！！！？？？……——、，，，");
        check(marks.sentences.isEmpty() && marks.comparedChars == 0 && marks.rate == 0f,
                "punctuation-only text contributes nothing");
        AigcDetector.Result seven = AigcDetector.detect("这句话共七个字。");
        check(seven.sentences.isEmpty() && seven.comparedChars == 0, "a seven-character sentence stays unscored");
        String gate = "这句话刚好八个字。";
        AigcDetector.Result eight = AigcDetector.detect(gate);
        check(eight.sentences.size() == 1, "an eight-character sentence is right on the gate and gets scored");
        check(eight.comparedChars == TextCorpus.validCount(TextCorpus.normalize(gate), 0, gate.length()),
                "the scored sentence contributes exactly its own characters to comparedChars");
        String mixed = "好的。这一段足够长的正文讲的是标注现场的取舍与返工，短句只有一句。";
        AigcDetector.Result only = AigcDetector.detect(mixed);
        check(only.sentences.size() == 1, "only the long sentence of a mixed paragraph is scored");
        check(only.comparedChars == TextCorpus.validCount(TextCorpus.normalize(mixed), 3, mixed.length()),
                "comparedChars ignores the discarded short sentence");
    }

    private static void offsets() {
        String emoji = "结果\uD83D\uDC49综上所述，本研究通过对数据的全面分析，可以发现该方法显著提升了效率。";
        AigcDetector.Result result = AigcDetector.detect(emoji);
        check(result.sentences.size() == 1, "the surrogate pair does not split the sentence");
        AigcDetector.Sentence sentence = result.sentences.get(0);
        String slice = emoji.substring(sentence.start, sentence.end);
        check(slice.equals(emoji), "the offsets slice the whole original string including the emoji");
        check(sentence.start == 0 && sentence.end == emoji.length(), "offsets are UTF-16 indices of the input string");
        // instrument-19 起段分带着四条用词判据：判定规模之外（不足 120 有效字 / 4 句）的段落，
        // 加不加这三个字符的前缀实测差 2.38 分（带前缀 5.89 对不带 8.28），字组类型比与熵在几十个字
        // 上根本稳不住。所以"前缀不许挪分"这句话只在判定规模之内讲得住；规模之外的那一段本来就不许
        // 单独定档，这里把门槛本身钉住。
        AigcRhythm.Reading tiny = AigcRhythm.measure(emoji, 0, emoji.length());
        check(tiny.validChars < AigcRhythm.flagChars() && tiny.sentences < AigcRhythm.flagSentences()
                        && !tiny.suspicious,
                "带代理对的这一句只有 " + tiny.validChars + " 有效字 / " + tiny.sentences
                        + " 句，够不到判定规模（" + AigcRhythm.flagChars() + " 字 / "
                        + AigcRhythm.flagSentences() + " 句），仪器不给它单独定档");
        String pair = "结果" + new String(Character.toChars(0x1F449));
        String longEmoji = pair + AI;
        AigcDetector.Result withEmoji = AigcDetector.detect(longEmoji);
        AigcDetector.Result withoutEmoji = AigcDetector.detect(AI);
        double shift = Math.abs(withEmoji.sentences.get(0).score - withoutEmoji.sentences.get(0).score);
        check(withEmoji.sentences.get(0).start == 0
                        && longEmoji.substring(withEmoji.sentences.get(0).start,
                                withEmoji.sentences.get(0).end)
                                .indexOf(new String(Character.toChars(0x1F449))) > 0
                        && shift < 1d,
                "判定规模之内（" + AigcRhythm.measure(longEmoji, 0, longEmoji.length()).validChars
                        + " 有效字）三个字符的代理对前缀只把段分挪动 " + fmt(shift)
                        + " 分（带前缀 " + withEmoji.sentences.get(0).score + "，去掉前缀 "
                        + withoutEmoji.sentences.get(0).score + "），偏移照样指到整句");
        boolean exact = true;
        boolean ordered = true;
        ArrayList<int[]> spans = TextCorpus.sentences(HUMAN);
        AigcDetector.Result human = AigcDetector.detect(HUMAN);
        for (int i = 0; i < human.sentences.size(); i++) {
            AigcDetector.Sentence item = human.sentences.get(i);
            if (item.start < 0 || item.end > HUMAN.length() || item.end <= item.start) ordered = false;
            if (i > 0 && item.start < human.sentences.get(i - 1).end) ordered = false;
            String text = HUMAN.substring(item.start, item.end);
            if (text.length() == 0) exact = false;
            boolean found = false;
            for (int k = 0; k < spans.size(); k++) {
                int[] span = spans.get(k);
                if (span[0] == item.start && span[1] == item.end) found = true;
            }
            if (!found) exact = false;
        }
        check(ordered, "scored sentences are ordered and never overlap");
        check(exact, "every slice rebuilds a real sentence of the original text");
        String fullwidth = "Ｐａｐｅｒ　结论部分：综上所述，该方法在噪声环境下仍然稳定，成本也降了下来。";
        AigcDetector.Result folded = AigcDetector.detect(fullwidth);
        boolean widthOk = folded.sentences.size() >= 1;
        for (int i = 0; widthOk && i < folded.sentences.size(); i++) {
            AigcDetector.Sentence item = folded.sentences.get(i);
            if (fullwidth.substring(item.start, item.end).indexOf('Ｐ') < 0
                    && fullwidth.substring(item.start, item.end).indexOf('综') < 0) widthOk = false;
        }
        check(widthOk, "fullwidth text keeps original-width slices at the reported offsets");
    }

    private static void metrics() {
        String[] samples = {HUMAN, MILD, AI, AI_EN, HUMAN_EN};
        boolean charsAddUp = true;
        boolean weighted = true;
        boolean bounded = true;
        for (int i = 0; i < samples.length; i++) {
            AigcDetector.Result result = AigcDetector.detect(samples[i]);
            String norm = TextCorpus.normalize(samples[i]);
            int sum = 0;
            double score = 0d;
            for (int k = 0; k < result.sentences.size(); k++) {
                AigcDetector.Sentence sentence = result.sentences.get(k);
                int chars = TextCorpus.validCount(norm, sentence.start, sentence.end);
                sum += chars;
                score += (double) sentence.score * chars;
                if (Float.isNaN(sentence.score) || Float.isInfinite(sentence.score)) bounded = false;
            }
            if (sum != result.comparedChars) charsAddUp = false;
            double expected = sum == 0 ? 0d : score / sum;
            if (Math.abs(expected - result.rate) > 0.0005d) weighted = false;
        }
        check(charsAddUp, "comparedChars equals the characters of the sentences that were scored");
        // rate 的定义是本次重构的不可谈判约束：区间聚合必须做成"改视图不改数"，这条是那条恒等式的支点。
        check(weighted, "rate 还是字符加权均值，口径一个字没改（区别只在它不再乘 100、也不叫比例）");
        check(bounded, "每个句分都是有限的数（段级刻度可正可负，不再夹在 0 到 1 里）");
        AigcDetector.Result twice = AigcDetector.detect(AI);
        AigcDetector.Result again = AigcDetector.detect(AI);
        boolean same = twice.rate == again.rate && twice.sentences.size() == again.sentences.size();
        for (int i = 0; same && i < twice.sentences.size(); i++) {
            same = twice.sentences.get(i).score == again.sentences.get(i).score
                    && twice.sentences.get(i).features.equals(again.sentences.get(i).features);
        }
        check(same, "detect is pure: a second call reproduces the first one exactly");
    }

    private static void profiles() {
        String semis = "第二组只看词频；第三组加了句法特征；第四组把两者拼在一起跑了一夜。";
        check(hasFeature(AigcDetector.detect(semis), "分号"), "semicolon profile is reported");
        String dash = "第三组把两者拼在一起——结果出人意料，第二组反而是最差的那一组。";
        check(hasFeature(AigcDetector.detect(dash), "破折号"), "dash profile is reported");
        String parallel = "实验分了四组，覆盖了三种语言，跑了两个星期，换过三次参数，最后只剩一组能看。";
        check(hasFeature(AigcDetector.detect(parallel), "并列排比密集"), "comma-heavy parallel rhythm is reported");
        String repeat = "数据质量决定模型质量，模型质量决定结果质量，结果质量决定应用质量，应用质量决定业务质量。";
        check(hasFeature(AigcDetector.detect(repeat), "多样度"), "reused vocabulary shows up as a diversity feature");
    }
    private static void edgeCases() {
        AigcDetector.Result nullResult = AigcDetector.detect(null);
        check(nullResult.sentences.isEmpty() && nullResult.rate == 0f && nullResult.comparedChars == 0,
                "detect(null) returns an empty result instead of throwing");
        check(AigcDetector.detect("").rate == 0f, "detect of an empty string is zero");
        check(AigcDetector.detect("   \t\n  ").comparedChars == 0, "whitespace-only text scores nothing");
        // 本版有意改掉的一条：0 个计分句过去留着 insufficientSample=false，报告那一格印 0.00%。
        AigcDetector.Result empty = AigcDetector.detect("");
        check(empty.tier == AigcDetector.Tier.INSUFFICIENT_SAMPLE && empty.insufficientSample
                && AigcDetector.measurement(empty).indexOf("样本不足") == 0
                && empty.verdict.indexOf("样本不足") == 0
                && empty.verdict.indexOf("AI 倾向指数") < 0,
                "空正文归样本不足：那一格只说样本不足，指数、可疑字数、档位一个都不出口");
        check(nullResult.tier == AigcDetector.Tier.INSUFFICIENT_SAMPLE
                && AigcDetector.detect("   \t\n  ").tier == AigcDetector.Tier.INSUFFICIENT_SAMPLE,
                "null 与纯空白同样只给样本不足，不给比例");
        AigcDetector.Result marks = AigcDetector.detect("……———，，；；");
        check(marks.rate == 0f && marks.sentences.isEmpty()
                && marks.tier == AigcDetector.Tier.INSUFFICIENT_SAMPLE,
                "纯标点不出句，也只给样本不足");
        StringBuilder runOn = new StringBuilder();
        for (int i = 0; i < 60; i++) runOn.append("这一段的记录没有标点只能一直写下去");
        AigcDetector.Result longRun = AigcDetector.detect(runOn.toString());
        check(longRun.sentences.size() == 1 && longRun.comparedChars == runOn.length(),
                "a punctuation-free run of " + runOn.length() + " characters is treated as one sentence");
        check(Float.isFinite(longRun.rate), "一句到底的长稿子算得出有限的指数（" + longRun.rate + "）");
        check(longRun.segments.size() == 1 && longRun.segments.get(0).toSentence == 0,
                "一句到底的稿子只有一个区间");
        AigcDetector.Result numbers = AigcDetector.detect("１２３４５６７８９０，２０２０ 年到 ２０２４ 年的样本量分别是三百、五百、八百。");
        check(Float.isFinite(numbers.rate), "全角数字混排的稿子不炸，指数有限（" + numbers.rate + "）");
        check(AigcDetector.detect("综上所述").sentences.isEmpty(), "a bare template phrase is too short to score");
        // 排除区间盖住整篇：没有计分句，区间表也必须是空的，档位回到样本不足。
        AigcDetector.Result covered = AigcDetector.detect(AI, new int[]{0, AI.length()});
        check(covered.segments.isEmpty() && covered.excludedChars > 0
                && covered.tier == AigcDetector.Tier.INSUFFICIENT_SAMPLE,
                "整篇圈成引用之后没有区间可报，档位是样本不足");
        // 排除区间插在中间：过去能并成一段的四句现在被切成两段，两侧都不越界。
        String hot = "综上所述，通过上述分析可以发现，这套流程的稳定性还有提升空间，为后续优化提供了重要的参考。";
        String cite = "总而言之，这段引文本身也是套话连篇，为论证提供了依据，具有重要的理论和实践意义。";
        String around = hot + cite + hot;
        AigcDetector.Result merged = AigcDetector.detect(around);
        check(merged.segments.size() == 1, "同一自然段里连着的同腔句并成一个区间");
        AigcDetector.Result split = AigcDetector.detect(around, new int[]{hot.length(), hot.length() + cite.length()});
        check(split.segments.size() == 2, "排除区间插在中间时区间在这里断开");
        boolean crosses = false;
        for (int i = 0; i < split.segments.size(); i++) {
            AigcDetector.Segment seg = split.segments.get(i);
            if (seg.start < hot.length() + cite.length() && seg.end > hot.length()) crosses = true;
        }
        check(!crosses, "没有区间跨过排除边界，跳正文不会跳到引用里");
        // 一句超过区间字数上限：自成一段且不拆。
        StringBuilder monster = new StringBuilder();
        while (monster.length() < 1400) monster.append("这一句被刻意写得很长以至于超过区间字数上限的设定");
        AigcDetector.Result giant = AigcDetector.detect(monster.toString() + "。");
        check(giant.sentences.size() == 1 && giant.segments.size() == 1,
                "一千四百字的一句话独占一个区间，本版不拆（拆分留给未来的窗口化）");
    }

    private static void performance() {
        StringBuilder builder = new StringBuilder();
        while (builder.length() < 20000) {
            builder.append(HUMAN).append(AI);
        }
        String text = builder.substring(0, 20000);
        AigcDetector.detect(text);
        long best = Long.MAX_VALUE;
        AigcDetector.Result result = null;
        for (int round = 0; round < 3; round++) {
            long began = System.nanoTime();
            result = AigcDetector.detect(text);
            long millis = (System.nanoTime() - began) / 1000000L;
            if (millis < best) best = millis;
        }
        check(result != null && result.comparedChars > 19000, "the 20k-character draft is fully compared");
        // 0.7.0 改写：原 `20f < rate < 90f` → 下限 10。人写段落的句子在新表下大量归零，混合稿实测 14.53；
        // 上限不动。这条仍守着"混合稿明显高于纯人写"（纯人写实测 4.35）。
        check(result != null && Float.isFinite(result.rate)
                        && result.rate > AigcRhythm.humanP99() && result.flaggedChars > 0,
                "两万字的混合稿整篇顶在真人 P99 之上（实测 " + (result == null ? 0 : result.rate)
                        + "，线 " + AigcRhythm.humanP99() + "），可疑字数 "
                        + (result == null ? 0 : result.flaggedChars) + " 字");
        check(best < 3000L, "detect over 20k characters stays fast on a desktop JVM (measured " + best + " ms)");
        System.out.println("PERF detect(20000 chars) = " + best + " ms, sentences="
                + (result == null ? 0 : result.sentences.size()));
    }

    /** 系数生效：ROADMAP 那句"权重集中成一组可标定的系数"的可执行版本。 */
    private static void coefficients() {
        AigcScorer.Coefficients base = AigcScorer.defaults(AigcFamily.CHINESE);
        check(base.version().length() > 0, "系数表带版本号，报告能追溯是哪一版算出来的");
        check("v1-order-only".equals(base.version()),
                "本轮没有一条系数够格定值，版本号直说只定了序（" + base.version() + "）");
        check(base.get(AigcFeatureId.TEMPLATE) > 0d
                        && base.get(AigcFeatureId.PARALLEL) < base.get(AigcFeatureId.TEMPLATE),
                "模板句式的系数高于节奏类系数（按真人侧触发率定序的结果）");
        check(base.get(AigcFeatureId.PUNCT_DASH) < base.get(AigcFeatureId.PARALLEL),
                "破折号那条无出处的系数被压在节奏组门面系数之下（" + base.get(AigcFeatureId.PUNCT_DASH)
                        + " < " + base.get(AigcFeatureId.PARALLEL) + "）");
        double total = 0d;
        double strongest = 0d;
        for (String label : base.groupTotals().keySet()) {
            double weight = base.groupTotals().get(label).doubleValue();
            total += weight;
            if (weight > strongest) strongest = weight;
        }
        check(total <= 1.0d + 1e-9d, "四组系数加和不超过 1.0（实测 " + total + "）");
        check(strongest < AigcDetector.SEGMENT_FLAG_GATE,
                "任何一条单独的证据都不足以把一句推到可疑（最强一组 " + strongest + " < 门槛 "
                        + AigcDetector.SEGMENT_FLAG_GATE + "）");
        String one = "综上所述，我们在四个数据集上把同一组实验重跑了一遍，结论和上次差不多。";
        /* 2026-10-12 起 detect() 的句分出自随包判据表，AigcScorer 只剩"给证据起名字"这一件事。
           所以"系数生效"这把尺子改成直接量 AigcScorer.score：标定台还得能用它试系数，
           而句分不许再跟着它动——那才是换掉这台仪器的真正含义。 */
        ArrayList<AigcFeatures.Hit> hits = AigcFeatures.of(segment(one), statsOf(one));
        check(!hits.isEmpty(), "这句套话照样列得出证据（" + hits.size() + " 条），证据这一侧没被动过");
        double value = AigcScorer.score(hits, base);
        check(value > 0d, "启发式那套给这句算出 " + value + "（只留给标定台与旧账核对，不进正文读数）");
        AigcScorer.Coefficients halved = AigcScorer.defaults(AigcFamily.CHINESE);
        halved.set(AigcFeatureId.TEMPLATE, base.get(AigcFeatureId.TEMPLATE) / 2d);
        double half = AigcScorer.score(hits, halved);
        check(half < value, "标定口子把模板句式的系数减半，AigcScorer.score 立刻跟着降（"
                + value + " → " + half + "）");
        AigcScorer.overrideCoefficients(halved);
        AigcDetector.Result turned;
        try {
            turned = AigcDetector.detect(one);
        } finally {
            AigcScorer.restoreCoefficients();
        }
        check(turned.sentences.get(0).score == AigcDetector.detect(one).sentences.get(0).score,
                "句分不再跟着启发式系数走：把系数减半再还原，句分一个字都不动（"
                        + turned.sentences.get(0).score + "）");
        check(turned.sentences.get(0).features.equals(AigcDetector.detect(one).sentences.get(0).features),
                "改系数不改特征：判定依据照旧逐条列出来");
        check(AigcScorer.score(null, base) == 0d && AigcScorer.score(new ArrayList<AigcFeatures.Hit>(), base) == 0d,
                "没有触发项就是零分，打分不看文本也不猜");
    }

    /** 每条特征能单独算、单独断言；族与族之间不串表。 */
    private static void features() {
        String flat = "第一段话也是一样的长。第二段话也是同样的长。第三段话仍旧是一样长。第四段话还是一般长度。";
        AigcFeatures.DocStats flatStats = statsOf(flat);
        AigcFeatures.Segment flatFirst = segment(flat);
        check(flatStats.scoredSentences == 4, "四句等长的段落四句都计分");
        check(AigcFeatures.value(AigcFeatureId.BURST, flatFirst, flatStats) > 0.9d,
                "四句等长的文档里，突发度这条特征取到接近满值（"
                        + AigcFeatures.value(AigcFeatureId.BURST, flatFirst, flatStats) + "）");
        check(AigcFeatures.value(AigcFeatureId.TEMPLATE, flatFirst, flatStats) == 0d,
                "同一段落里没写套话就是零，特征之间不互相污染");
        AigcFeatures.DocStats jumpy = statsOf(HUMAN);
        check(AigcFeatures.value(AigcFeatureId.BURST, segment(HUMAN), jumpy) == 0d,
                "长短悬殊的段落里突发度不亮灯");
        String three = "第一段话也是一样的长。第二段话也是同样的长。第三段话仍旧是一样长。";
        check(AigcFeatures.value(AigcFeatureId.BURST, segment(three), statsOf(three)) == 0d,
                "计分句不足四句的文档不给突发度，文档级证据不硬凑");
        ArrayList<AigcFeatures.Hit> heavy = AigcFeatures.of(segment(AI_SENTENCE_1), statsOf(AI));
        check(heavy.size() >= 2, "模板腔句子一次就亮出两条以上独立特征（" + heavy.size() + " 条）");
        boolean graded = true;
        for (AigcFeatureId id : AigcFeatureId.values())
            if (AigcFeatureId.COUNT <= id.ordinal()) graded = false;
        check(graded && AigcFeatureId.COUNT == AigcFeatureId.values().length,
                "特征登记表没有漏项，新增特征必须同时进系数表");
        boolean grouped = true;
        for (AigcFeatureId id : AigcFeatureId.values()) {
            int g = AigcFeatureId.group(id);
            if (g < 0 || g >= AigcFeatureId.GROUP_COUNT) grouped = false;
        }
        check(grouped && AigcFeatureId.GROUP_COUNT == AigcFeatureId.GROUP_LABELS.length,
                "每条特征都登记了证据组，组数与组名一样多");
        check(AigcFeatures.value(AigcFeatureId.TEMPLATE, segment("综上所述，结论是稳定的。"), statsOf(MILD)) > 0d,
                "模板特征只在真有套话的那句上亮灯");
        // 分族不串表：拉丁句不数中文连接词，中文句不数英文连接词。
        String latinWithCn = "the loader stalled halfway, 首先 其次 因此 we restarted the run.";
        AigcFeatures.Segment latin = segment(latinWithCn);
        check(latin.family == AigcFamily.LATIN, "拉丁字母占多的一句判为拉丁族（" + latin.family + "）");
        check(AigcFeatures.countOccurrences(latin.body, AigcFeatures.CN_CONNECTIVES) >= 3,
                "这句里确实塞了三个以上中文连接词，才拿它当分族的量尺");
        check(AigcFeatures.value(AigcFeatureId.CONNECTIVE, latin, statsOf(latinWithCn)) == 0d,
                "拉丁族不数中文连接词表——0.5.4 中英混数那个毛病已经没了");
        String cnWithEn = "这套流程在凌晨三点半卡住了，moreover 和 furthermore 都没能把日志救回来。";
        AigcFeatures.Segment chinese = segment(cnWithEn);
        check(chinese.family == AigcFamily.MIXED,
                "夹了两个英文连接词的中文句判为混排族，不会被当成整句英文（" + chinese.family + "）");
        check(AigcFeatures.countOccurrences(chinese.body, AigcFeatures.EN_CONNECTIVES) == 2,
                "这句里确实有两个英文连接词");
        check(AigcFeatures.value(AigcFeatureId.CONNECTIVE, chinese, statsOf(cnWithEn)) == 0d,
                "混排族只按中文连接词表数，英文连接词不掺进来加分");
        check(AigcFeatures.diversityFloor(AigcFamily.LATIN) != AigcFeatures.diversityFloor(AigcFamily.CHINESE),
                "多样度下限分族（拉丁 " + AigcFeatures.diversityFloor(AigcFamily.LATIN)
                        + " 对中文 " + AigcFeatures.diversityFloor(AigcFamily.CHINESE) + "）");
        check(AigcScorer.defaults(AigcFamily.LATIN).version().indexOf("latin") >= 0
                        && !AigcScorer.defaults(AigcFamily.LATIN).version()
                        .equals(AigcScorer.defaults(AigcFamily.CHINESE).version()),
                "拉丁族的版本号单独标注，未经标定的事写在版本号里");
    }
    /** 区间聚合：分区不挑选 + 恒等式 + 不跨段/不跨族/不跨排除区。 */
    private static void segments() {
        String[] texts = {AI + MILD + HUMAN, HEAVY + HUMAN, MILD + AI_EN, HUMAN_EN + AI};
        for (int t = 0; t < texts.length; t++) {
            String text = texts[t];
            AigcDetector.Result run = AigcDetector.detect(text);
            String norm = TextCorpus.normalize(text);
            check(!run.segments.isEmpty() && run.segments.size() <= run.sentences.size(),
                    "区间数不会多于句子数（" + run.segments.size() + " <= " + run.sentences.size() + "）");
            int chars = 0, members = 0, first = -1;
            double weighted = 0d;
            boolean partition = true, ordered = true;
            for (int i = 0; i < run.segments.size(); i++) {
                AigcDetector.Segment seg = run.segments.get(i);
                chars += seg.chars;
                members += seg.toSentence - seg.fromSentence + 1;
                weighted += (double) seg.score * seg.chars;
                if (seg.start != run.sentences.get(seg.fromSentence).start
                        || seg.end != run.sentences.get(seg.toSentence).end) partition = false;
                if (seg.chars != expectedChars(run, seg, norm)) partition = false;
                for (int k = seg.fromSentence; k <= seg.toSentence; k++) {
                    AigcDetector.Sentence member = run.sentences.get(k);
                    if (member.start < seg.start || member.end > seg.end) partition = false;
                }
                if (i > 0 && seg.start < run.segments.get(i - 1).end) ordered = false;
                if (first < 0) first = seg.start;
            }
            check(ordered, "区间按原文顺序排且不重叠");
            check(partition, "区间偏移不扩边，字数按成员句有效字符算");
            check(members == run.sentences.size(), "每个计分句恰好落进一个区间，低分句也自成区间");
            check(chars == run.comparedChars, "区间字数加和等于 comparedChars（" + chars
                    + " == " + run.comparedChars + "）");
            // 这条是"分区不挑选"的恒等式：区间只是视图，聚合一个字符都没有重复计或漏掉。
            // instrument-19 起 rate 就是段级刻度本身（不再乘 100、也不叫比例），这里不许再乘回去。
            check(Math.abs(weighted / Math.max(1, chars) - run.rate) < 0.05d,
                    "Σ 区间分×字数 / Σ 字数 == rate 是恒等式（" + run.rate + "）");
            check(run.segments.get(0).start == first, "第一个区间的起点就是第一个计分句的起点");
        }
        String mixed = AI + MILD + MILD + MILD;
        int aiEnd = AI.length();
        AigcDetector.Result quotedRun = AigcDetector.detect(mixed, new int[]{0, aiEnd});
        boolean clean = true;
        for (int i = 0; i < quotedRun.segments.size(); i++)
            if (quotedRun.segments.get(i).start < aiEnd) clean = false;
        check(clean, "被圈成引用的那一段把区间切断了，没有区间跨过排除边界");
        String mixedFamily = "We ran it overnight and it choked halfway. 综上所述，这套流程的稳定性还有提升空间。";
        AigcDetector.Result families = AigcDetector.detect(mixedFamily);
        boolean split = true;
        for (int i = 0; i < families.sentences.size(); i++) {
            if (families.sentences.get(i).score < AigcDetector.SEGMENT_SCORE_FLOOR) continue;
            boolean found = false;
            for (int k = 0; k < families.segments.size(); k++) {
                AigcDetector.Segment seg = families.segments.get(k);
                if (i >= seg.fromSentence && i <= seg.toSentence) {
                    found = true;
                    if (seg.fromSentence != seg.toSentence) split = false;
                }
            }
            if (!found) split = false;
        }
        check(split, "中文句和英文句不并成同一个区间（族切换即断）");
        AigcDetector.Result lone = AigcDetector.detect(AI_SENTENCE_1 + MILD + MILD + MILD);
        check(lone.segments.get(0).score == lone.sentences.get(0).score,
                "单句区间的区间分就等于句分——区间分数里没有夹带连续加成");
        // instrument-19 起"过线"是段级门槛（>=120 有效字且 >=4 句，优先读表头 gate_min_*）判出来的，
        // 过线的段不可能只剩一句，所以这两条改用真过线的样例：可疑字数与最长区间只对过线的区间负责。
        AigcDetector.Result flagged = AigcDetector.detect(AI + "\n" + MILD + "\n" + MILD);
        check(flagged.flaggedSegments == 1 && flagged.longestRunSentences >= 1
                        && flagged.longestRunChars > 0,
                "最长可疑区间的句数与字数按有效字符报出来，档位的依据不是黑箱（"
                        + flagged.longestRunSentences + " 句 / " + flagged.longestRunChars + " 字）");
        check(flagged.flaggedChars == flagged.longestRunChars
                        && flagged.flaggedChars < flagged.comparedChars,
                "可疑字数只从过线的区间里来（" + flagged.flaggedChars + " 字 / 参与判定 "
                        + flagged.comparedChars + " 字）");
        AigcDetector.Result quiet = AigcDetector.detect(HUMAN_EN + HUMAN_EN + HUMAN_EN);
        check(quiet.flaggedChars == 0 && quiet.flaggedSegments == 0 && quiet.longestRunSentences == 0,
                "没有过线区间时三个可疑量数全为 0，不靠巧合");
    }

    /** 五档边界 + 双门槛：比例与字数缺一样就降档。 */
    private static void tiers() {
        check(AigcDetector.detect("").tier == AigcDetector.Tier.INSUFFICIENT_SAMPLE
                        && AigcDetector.measurement(AigcDetector.detect("")).indexOf("样本不足") == 0,
                "空正文归样本不足，比例那一格闭嘴（未标定时闭得更早，连档位也不说）");
        check(AigcDetector.detect("！！！？？？……——、，，，").tier
                        == AigcDetector.Tier.INSUFFICIENT_SAMPLE,
                "只有标点也只给样本不足，不给比例");
        check(AigcDetector.detect(HUMAN).tier == AigcDetector.Tier.INSUFFICIENT_SAMPLE,
                "两百字的人写段落先落样本不足档，比例和档位一起闭嘴");
        AigcDetector.Result none = AigcDetector.detect(HUMAN_EN + HUMAN_EN + HUMAN_EN);
        check(none.tier == AigcDetector.Tier.NONE, "过了字数门槛的真人英文给一般档");
        // 段级口径下同一段落重复三遍会把四条用词判据算歪（实测连写成一段只有 2.08 分，谁都过不了线），
        // 所以档位样例一律照真论文那样换行分段——与上面 guards() 里那份长样例同一个口径。
        AigcDetector.Result watch = AigcDetector.detect(AI + "\n" + MILD + "\n" + MILD);
                check(watch.rate >= AigcRhythm.humanP90()
                        && watch.flaggedChars < AigcDetector.TIER_REVIEW_CHARS
                        && watch.tier == AigcDetector.Tier.WATCH,
                "指数过真人九成线但可疑字数不到 " + AigcDetector.TIER_REVIEW_CHARS
                        + " 字时降一档（实测指数 " + watch.rate + "、可疑 " + watch.flaggedChars
                        + " 字、档位 " + watch.tier + "）");
        AigcDetector.Result lone = AigcDetector.detect(AI_SENTENCE_1 + HUMAN_EN + HUMAN_EN);
        check(lone.comparedChars >= AigcDetector.MIN_DOCUMENT_CHARS
                        && lone.longestRunSentences < AigcDetector.TIER_REVIEW_RUN
                        && lone.tier.ordinal() < AigcDetector.Tier.NEEDS_REVIEW.ordinal(),
                "单句高分不成档——最长可疑区间只有一句，够不到复核档（" + lone.tier + "）");
        AigcDetector.Result review = AigcDetector.detect(HEAVY + "\n" + MILD + "\n" + MILD);
        check(review.tier == AigcDetector.Tier.NEEDS_REVIEW,
                "成片且超过 " + AigcDetector.TIER_REVIEW_CHARS + " 字的机器腔给复核档（可疑 "
                        + review.flaggedChars + " 字 / 最长 " + review.longestRunSentences + " 句）");
        AigcDetector.Result strong = AigcDetector.detect(HEAVY + "\n" + HEAVY + "\n" + HEAVY);
        check(strong.tier == AigcDetector.Tier.STRONG,
                "整篇同腔且过了 " + AigcDetector.TIER_STRONG_CHARS + " 字给成段档（可疑 "
                        + strong.flaggedChars + " 字 / 最长 " + strong.longestRunSentences + " 句 / 比例 "
                        + strong.rate + "）");
        check(strong.tier.ordinal() > AigcDetector.Tier.NEEDS_REVIEW.ordinal()
                        && AigcDetector.Tier.NEEDS_REVIEW.ordinal() > AigcDetector.Tier.WATCH.ordinal()
                        && AigcDetector.Tier.WATCH.ordinal() > AigcDetector.Tier.NONE.ordinal(),
                "五档强弱顺序固定，报告与 UI 按 ordinal 比高低");
        // 百分数只有两处合法身份：②那一行的"可疑字数占参与判定正文的百分比"与划线误报率，
        // 以及④出处里那些定标读数。档位那一句、指数写法、刻度写法必须是裸分数——段分是一条
        // 相对刻度，一旦混进百分号就会被读成"多少比例是机器写的"。
        check(AigcDetector.measurement(strong).indexOf("%") < 0
                        && AigcDetector.measurement(review).indexOf("%") < 0
                        && AigcDetector.measurement(watch).indexOf("%") < 0
                        && AigcDetector.measurement(none).indexOf("%") < 0
                        && AigcDetector.indexText(strong.rate).indexOf("%") < 0
                        && AigcDetector.scaleText(strong.scaleP99).indexOf("%") < 0
                        && AigcRhythm.NOT_LOADED.indexOf("%") < 0,
                "档位那一句、指数写法与刻度写法里都不塞百分比，一个口径一个数（百分数只许待在②的占比"
                        + "与④的定标读数里）");
        // 档位那句话现在用户读不到（被 measurement() 外面那一层挡着），但它必须原样活着：
        // 方向验正之后回到眼前的就是这一句，届时不许悄悄换成另一套说法，所以这里继续逐字钉住。
        check(AigcDetector.measurement(strong).indexOf("成段机器腔") == 0
                        && AigcDetector.measurement(strong).indexOf("段") > 0,
                "成段档那句人话说清了几段几字（" + AigcDetector.measurement(strong) + "）");
    }

    /** 真实论文里最像机写的真人句子：钉子户清单，一句都不许进可疑档。 */
    private static void hardHuman() {
        ArrayList<String> nails = new ArrayList<String>();
        for (int i = 0; i < HARD_HUMAN.length; i++) nails.add(HARD_HUMAN[i]);
        try {
            for (String line : Files.readAllLines(Paths.get("tests/corpus/aigc-hard-human.txt"),
                    StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (text.length() == 0 || text.startsWith("#") || nails.indexOf(text) >= 0) continue;
                nails.add(text);
            }
        } catch (Exception missing) {
            // 语料文件不在就用内置的三句，套件不依赖工作目录也能跑。
        }
        int over = 0;
        float worst = 0f;
        AigcDetector.Tier top = AigcDetector.Tier.NONE;
        for (int i = 0; i < nails.size(); i++) {
            // 同一句念三遍，凑够 400 字的样本门槛，让档位判定也参与复检。
            AigcDetector.Result run = AigcDetector.detect(nails.get(i) + nails.get(i) + nails.get(i));
            for (int k = 0; k < run.sentences.size(); k++) {
                float score = run.sentences.get(k).score;
                if (score > worst) worst = score;
                if (score >= AigcDetector.SEGMENT_FLAG_GATE) over++;
            }
            if (run.tier.ordinal() > top.ordinal()) top = run.tier;
        }
        check(over == 0, "真实论文里最像机写的 " + nails.size() + " 句真人句子，一句都不许进可疑档"
                + "（最高 " + worst + "，门槛 " + AigcDetector.SEGMENT_FLAG_GATE + "，余量 "
                + (AigcDetector.SEGMENT_FLAG_GATE - worst) + "）");
        check(top.ordinal() < AigcDetector.Tier.NEEDS_REVIEW.ordinal(),
                "钉子户句子重复三遍也够不到复核档（实测 " + top + "）");
    }
}

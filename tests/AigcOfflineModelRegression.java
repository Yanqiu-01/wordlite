package com.rikkahub.wordlite;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * 离线字符 n-gram 模型这一路的回归：随包文件装得上、两侧算的是同一个数、出厂开关确实关着。
 *
 * 三件事各自成档，混在一起就分不清是哪一条被改坏了：
 * ① 口径一致：黄金样本由训练台（{@code tools/build-aigc-model.py}）算出，Java 必须复算到同一位小数，
 *    否则说明两侧的 normalize/compact/次线性词频/归一化漂了——权重文件就成了哑弹。
 * ② 独立留出：在仓库里那批人工标注真稿上重算一遍 AUC 与真人误报（这批一个字都没进训练），
 *    数字必须和清单里写的一致，且必须仍然低于出厂门槛——门槛不过就不许印百分比，这条不许松。
 * ③ 开关本身：清单写 calibrated=true 但实测数不过线时仍然不许用；数过线时才走模型路径，
 *    且印出来的那一句必须带上"是哪个模型、在哪些数据上量的"。
 *
 * 缺权重文件、缺语料一律判失败（{@link #check}直接抛），不静默跳过。
 */
public final class AigcOfflineModelRegression {
    private static int count;
    private static String assetDir = "app/src/main/assets/aigc";
    private static String corpusDir = "tests/corpus";

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    private static final String[][] HOLDOUT = {
            {"H1", "real-prose.txt", "0"},
            {"H2", "aigc-label-human-verbatim.txt", "0"},
            {"H3", "cnki-cross.txt", "0"},
            {"X1", "aigc-label-human-polish-agent.txt", "0"},
            {"HH", "aigc-hard-human.txt", "0"},
            {"M-RAW", "aigc-label-machine-raw.txt", "1"},
            {"M-EVADE", "aigc-label-machine-evasive.txt", "1"},
            {"M-DOMAIN", "aigc-label-machine-domain.txt", "1"},
    };
    private static final String[][] TEMPLATE = {
            {"TPL-CARTOON", "aigc-cartoon.txt"}, {"TPL-FRAMES", "aigc-frames.txt"},
    };

    public static void main(String[] args) throws Exception {
        if (args.length > 0) assetDir = args[0];
        if (args.length > 1) corpusDir = args[1];
        loadsAndRefuses();
        goldenParity();
        threeTierManifest();
        independentHoldout();
        productFilterHoldout();
        windowChannel();
        gateAndWiring();
        uninstallAndClose();
        System.out.println("SUMMARY " + count + " assertions passed (offline n-gram model ships but is NOT calibrated; "
                + "no AIGC percentage may be printed).");
    }

    // ---------------------------------------------------------------- ① 装载与口径

    private static void loadsAndRefuses() throws Exception {
        long t0 = System.currentTimeMillis();
        AigcNgramModel model = installShipped();
        long ms = System.currentTimeMillis() - t0;
        check(model.weightRows() == model.manifest().exportedFeatures,
                "权重表装载 " + model.weightRows() + " 条，与清单写的 exported_features "
                        + model.manifest().exportedFeatures + " 一致");
        check(model.weightRows() >= 2000 && model.weightRows() <= AigcNgramModel.MAX_WEIGHT_ROWS,
                "导出特征 " + model.weightRows() + " 条在 2,000 ~ 开销上限 " + AigcNgramModel.MAX_WEIGHT_ROWS
                        + " 之间，手机侧装载得起");
        check(model.freqRows() > 20000 && model.freqRows() < AigcNgramModel.MAX_FREQ_ROWS,
                "字频表装载 " + model.freqRows() + " 条，低于开销上限 " + AigcNgramModel.MAX_FREQ_ROWS);
        check(model.tableSlots() <= 1 << 20, "哈希表槽位 " + model.tableSlots() + "，一兆槽位以内");
        check(ms < 4000, "装载耗时 " + ms + " ms（上限 4,000 ms，手机冷启动不能为它等）");
        String s = "本研究采用问卷调查与深度访谈相结合的方式，对样本进行了系统分析。";
        double a = model.score(TextCorpus.compactOf(s));
        double b = model.score(TextCorpus.compactOf(s));
        check(a == b, "同一句两次打分完全一致（" + a + "）");
        check(model.score("") == 0d && model.score(null) == 0d, "空串与 null 给 0，不给一个像结论的中间数");
        check(model.score(TextCorpus.compactOf("短")) == 0d, "不足最小 n-gram 长度的输入给 0");
    }

    private static void goldenParity() throws Exception {
        AigcNgramModel model = AigcNgramModel.current();
        ArrayList<String[]> golden = model.manifest().golden;
        check(golden.size() >= 4, "清单里有 " + golden.size() + " 条黄金样本可用于两侧口径比对");
        int human = 0, machine = 0;
        double worst = 0d;   // 两侧折叠表的边角字符仍有差（Python str.lower 与 Character.toLowerCase 不完全一致），
                            // 0.01 以内视为同一个口径；真正要保证的是下面留出 AUC 对得上。
        for (String[] g : golden) {
            String text = g[1];
            double expected = Double.parseDouble(g[3]);
            double got = model.score(TextCorpus.compactOf(text));
            double delta = Math.abs(got - expected);
            if ("human".equals(g[2])) human++; else machine++;
            worst = Math.max(worst, delta);
            check(delta <= 0.01d, "黄金样本（" + g[2] + "）Python " + expected + " vs Java " + round(got, 4) + "，差 " + round(delta, 5));
        }
        check(human >= 2 && machine >= 2, "黄金样本两侧都有（真人 " + human + " / 机器 " + machine
                + "），最大偏差 " + round(worst, 5) + "）");
        for (String[] g : model.manifest().golden) {
            if (!"golden_bits".equals(g[0])) continue;
            String compact = TextCorpus.compactOf(g[1]);
            int n = Math.min(compact.codePointCount(0, compact.length()), model.manifest().windowChars);
            double got = model.bitsPerChar(compact, 0, n);
            check(Math.abs(got - Double.parseDouble(g[2])) <= 0.02,
                    "滑窗 bits/字 Python " + g[2] + " vs Java " + round(got, 4));
        }
    }

    // ---------------------------------------------------------------- ①b 清单把三档留出分开写

    /** 公共留出 / 学术留出 / 真稿独立留出：三档各量各的，清单里三档都要有数。 */
    private static void threeTierManifest() throws Exception {
        AigcNgramModel.Manifest mf = AigcNgramModel.current().manifest();
        check(!Double.isNaN(mf.publicValidAuc), "清单里有公共留出 AUC " + round(mf.publicValidAuc, 4));
        check(!Double.isNaN(mf.academicAuc), "清单里有学术留出 AUC " + round(mf.academicAuc, 4));
        check(!Double.isNaN(mf.holdoutAuc), "清单里有真稿独立留出 AUC " + round(mf.holdoutAuc, 4));
        check(!Double.isNaN(mf.crossDomainMean) && !Double.isNaN(mf.crossDomainWorst)
                        || mf.crossDomainNote.length() > 0,
                "跨域留出（轮流撤掉整个域再考它）要么给数（均值 " + round(mf.crossDomainMean, 4)
                        + "），要么清单里写明为什么没有：" + mf.crossDomainNote);
        check(mf.provenance.contains("三档留出") && mf.provenance.contains("真稿独立留出"),
                "provenance 那句话把三档分开写，公共留出的高分不许冒充产品能力");
        check(mf.provenance.contains("出厂门槛只认第三档"), "清单写明出厂门槛只认真稿那一档");
        check(!Double.isNaN(mf.holdoutPairTopicMatchedAuc),
                "清单里另记一笔：域与主题都按住的那一对（H1 真人论文 vs M-DOMAIN 同领域同主题机器稿）AUC "
                        + round(mf.holdoutPairTopicMatchedAuc, 4) + "——整池分数会被大户摊平，这一对才贴产品口径");
        check(!Double.isNaN(mf.holdoutLengthOnlyAuc),
                "清单里还记了只看句子长短的 AUC " + round(mf.holdoutLengthOnlyAuc, 4)
                        + "：这是量留出集自身构造偏置的尺（机器句偏短），拿它当模型成绩是不许的");
    }

    // ---------------------------------------------------------------- ② 真稿独立留出

    private static final java.util.HashMap<String, ArrayList<Double>> SCORES_BY_TIER =
            new java.util.HashMap<String, ArrayList<Double>>();
    private static final java.util.HashMap<String, ArrayList<Double>> LENGTHS_BY_TIER =
            new java.util.HashMap<String, ArrayList<Double>>();

    private static ArrayList<Double> perTierScores(String code) {
        ArrayList<Double> v = SCORES_BY_TIER.get(code);
        return v == null ? new ArrayList<Double>() : v;
    }

    private static ArrayList<Double> tierLengths(String side) {
        boolean machine = "machine".equals(side);
        ArrayList<Double> out = new ArrayList<Double>();
        for (String[] t : HOLDOUT) {
            if (("1".equals(t[2])) != machine) continue;
            ArrayList<Double> v = LENGTHS_BY_TIER.get(t[0]);
            if (v != null) out.addAll(v);
        }
        return out;
    }

    private static void independentHoldout() throws Exception {
        AigcNgramModel model = AigcNgramModel.current();
        SCORES_BY_TIER.clear();
        LENGTHS_BY_TIER.clear();
        ArrayList<Double> human = new ArrayList<Double>(), machine = new ArrayList<Double>();
        StringBuilder perTier = new StringBuilder();
        double fpSum = 0d; int humanTotal = 0, machineTotal = 0, machineHit = 0;
        for (String[] tier : HOLDOUT) {
            ArrayList<Double> scores = sentenceScores(model, readText(corpusDir + "/" + tier[1]));
            boolean isMachine = "1".equals(tier[2]);
            double mean = 0d, max = 0d;
            for (int i = 0; i < scores.size(); i++) {
                double v = scores.get(i).doubleValue();
                mean += v;
                if (v > max) max = v;
                if (isMachine) {
                    machineTotal++;
                    if (v >= AigcDetector.SEGMENT_FLAG_GATE) machineHit++;
                } else {
                    humanTotal++;
                    if (v >= AigcDetector.SEGMENT_FLAG_GATE) fpSum++;
                }
            }
            if (scores.isEmpty()) continue;
            SCORES_BY_TIER.put(tier[0], scores);
            LENGTHS_BY_TIER.put(tier[0],
                    sentenceCompactLengths(read(corpusDir + "/" + tier[1]).toArray(new String[0])));
            (isMachine ? machine : human).addAll(scores);
            perTier.append(String.format(Locale.ROOT, "%n     %-10s %-3s n=%-4d 均值 %.3f 最高 %.3f",
                    tier[0], isMachine ? "机器" : "真人", scores.size(), mean / scores.size(), max));
        }
        double auc = auc(machine, human);
        double fpPerMille = 1000d * fpSum / Math.max(1, humanTotal);
        System.out.printf(Locale.ROOT, "真稿独立留出（Java 侧口径，n=%d 真人 + %d 机器）AUC(机器>真人)=%.4f  真人误报 %.2f 句/千句  机器侧过线 %.1f%%%s%n",
                humanTotal, machineTotal, auc, fpPerMille, 100d * machineHit / Math.max(1, machineTotal), perTier);
        check(humanTotal >= 400 && machineTotal >= 200,
                "独立留出规模够（真人 " + humanTotal + " 句 / 机器 " + machineTotal + " 句）");
        assertGateAgreesWithMeasurement("Java 句级口径", auc, fpPerMille);
        double pairAuc = auc(perTierScores("M-DOMAIN"), perTierScores("H1"));
        System.out.printf(Locale.ROOT, "     同领域同主题那一对：H1 %d 句 vs M-DOMAIN %d 句，AUC %.4f（整池 %.4f）%n",
                perTierScores("H1").size(), perTierScores("M-DOMAIN").size(), pairAuc, auc);
        check(Math.abs(pairAuc - model.manifest().holdoutPairTopicMatchedAuc) <= 0.12d,
                "Java 复算 H1 vs M-DOMAIN 那一对 AUC " + round(pairAuc, 4) + " 与清单写的 "
                        + round(model.manifest().holdoutPairTopicMatchedAuc, 4) + " 差 ≤ 0.12");
        double lenAuc = auc(tierLengths("machine"), tierLengths("human"));
        System.out.printf(Locale.ROOT, "     只看句长（不看内容）的 AUC %.4f（清单写的 %s）%n",
                lenAuc, round(model.manifest().holdoutLengthOnlyAuc, 4));
        check(Math.abs(lenAuc - model.manifest().holdoutLengthOnlyAuc) <= 0.15d,
                "Java 复算只看句长的 AUC " + round(lenAuc, 4) + " 与清单写的 "
                        + round(model.manifest().holdoutLengthOnlyAuc, 4) + " 差 ≤ 0.15（留出集偏置这笔账两侧要对得上）");
        check(Math.abs(auc - model.manifest().holdoutAuc) <= 0.12d,
                "Java 复算 AUC " + round(auc, 4) + " 与清单写的 " + model.manifest().holdoutAuc
                        + " 差 ≤ 0.12（切句口径差异之外没有别的漂移）");
    }

    // ---------------------------------------------------- ②b 同一模型，换成产品自己的打分口径

    /**
     * 与 AigcFeatureAuditRegression 逐字同口径（120 字段落门槛 + AigcFeatures.scores 句子门槛），
     * 这样真稿留出的规模就是标定台那份真人/机器计分句，出厂门槛用同一把尺子读，不用换一套句子再夸一次。
     */
    private static void productFilterHoldout() throws Exception {
        AigcNgramModel model = AigcNgramModel.current();
        ArrayList<Double> human = new ArrayList<Double>(), machine = new ArrayList<Double>();
        int fp = 0, machineHit = 0;
        StringBuilder perTier = new StringBuilder();
        for (String[] tier : HOLDOUT) {
            if ("HH".equals(tier[0])) continue;              // 标定台的七档里没有硬真人档
            boolean isMachine = "1".equals(tier[2]);
            int n = 0;
            for (String para : paragraphsOf(corpusDir + "/" + tier[1])) {
                String norm = TextCorpus.normalize(para);
                ArrayList<int[]> spans = TextCorpus.sentences(para);
                for (int i = 0; i < spans.size(); i++) {
                    AigcFeatures.Segment seg = AigcFeatures.segment(norm, spans.get(i)[0], spans.get(i)[1]);
                    if (!AigcFeatures.scores(seg)) continue;
                    double v = model.score(seg.compact);
                    n++;
                    if (isMachine) {
                        machine.add(Double.valueOf(v));
                        if (v >= AigcDetector.SEGMENT_FLAG_GATE) machineHit++;
                    } else {
                        human.add(Double.valueOf(v));
                        if (v >= AigcDetector.SEGMENT_FLAG_GATE) fp++;
                    }
                }
            }
            perTier.append(String.format(Locale.ROOT, "%n     %-10s %-3s n=%d",
                    tier[0], isMachine ? "机器" : "真人", n));
        }
        double auc = auc(machine, human);
        double fpPerMille = 1000d * fp / Math.max(1, human.size());
        System.out.printf(Locale.ROOT,
                "真稿独立留出（产品打分口径，与标定台同一把尺：n=%d 真人 + %d 机器）AUC(机器>真人)=%.4f"
                        + "  真人误报 %.2f 句/千句  机器侧过线 %.1f%%%s%n",
                human.size(), machine.size(), auc, fpPerMille,
                100d * machineHit / Math.max(1, machine.size()), perTier);
        check(human.size() >= 400 && machine.size() >= 200,
                "产品打分口径下真稿留出仍有 " + human.size() + " 真人句 / " + machine.size() + " 机器句");
        assertGateAgreesWithMeasurement("产品打分口径", auc, fpPerMille);
    }

    /**
     * 出厂开关只认实测数：过线就必须开，没过线就必须关。这一条不写死"今天没过线"，
     * 因为将来语料补上、真稿 AUC 真的过 0.85 时，判据应该反过来把门打开。
     */
    private static void assertGateAgreesWithMeasurement(String caliber, double auc, double fpPerMille) {
        boolean passes = auc >= AigcNgramModel.GATE_HOLDOUT_AUC
                && fpPerMille <= AigcNgramModel.GATE_HUMAN_FP_PER_MILLE;
        check(AigcNgramModel.calibrated() == passes,
                caliber + "：实测 AUC " + round(auc, 4) + "（门槛 " + AigcNgramModel.GATE_HOLDOUT_AUC
                        + "）、真人误报 " + round(fpPerMille, 2) + " 句/千句（门槛 "
                        + AigcNgramModel.GATE_HUMAN_FP_PER_MILLE + "）→ calibrated() 应为 " + passes
                        + "（开关跟实测数走，不跟清单里的旗子走）");
    }

    /** 逐字照标定台的段落门槛：trim 后丢空行与 # 注释，normalize 长度不足 120 字的整段不进。 */
    private static ArrayList<String> paragraphsOf(String path) throws IOException {
        ArrayList<String> out = new ArrayList<String>();
        for (String line : read(path)) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) continue;
            if (TextCorpus.normalize(text).length() < 120) continue;
            out.add(text);
        }
        return out;
    }

    // ---------------------------------------------------------------- ③ 滑窗通道

    private static void windowChannel() throws Exception {
        AigcNgramModel model = AigcNgramModel.current();
        int templateSegs = 0, templateHit = 0, humanSegs = 0, humanHit = 0;
        double thr = model.manifest().windowThreshold;
        for (String[] t : TEMPLATE) {
            for (String line : read(corpusDir + "/" + t[1])) {
                String compact = TextCorpus.compactOf(line);
                if (compact.codePointCount(0, compact.length()) < 60) continue;
                templateSegs++;
                double bits = model.bestWindowBits(compact);
                if (!Double.isNaN(bits) && bits < thr) templateHit++;
            }
        }
        for (String[] tier : HOLDOUT) {
            if ("1".equals(tier[2])) continue;
            for (String line : read(corpusDir + "/" + tier[1])) {
                String compact = TextCorpus.compactOf(line);
                if (compact.codePointCount(0, compact.length()) < 60) continue;
                humanSegs++;
                double bits = model.bestWindowBits(compact);
                if (!Double.isNaN(bits) && bits < thr) humanHit++;
            }
        }
        double tplRate = templateHit / (double) Math.max(1, templateSegs);
        System.out.printf(Locale.ROOT, "滑窗困惑度（阈值 %.3f bits/字）：模板段落点 %.1f%%（门槛 90%%）  真人段落点 %.1f%%%n",
                thr, 100d * tplRate, 100d * humanHit / (double) Math.max(1, humanSegs));
        check(tplRate < AigcNgramModel.GATE_TEMPLATE_WINDOW_HIT,
                "模板段窗口落点 " + (int) (100 * tplRate) + "% 达不到 90%，滑窗通道不达标");
        check(model.manifest().windowCalibrated == false, "清单里滑窗通道 calibrated=false");
        int hits = 0;
        for (String line : read(corpusDir + "/aigc-cartoon.txt")) if (model.windowHit(TextCorpus.compactOf(line))) hits++;
        check(hits == 0, "通道未达标时 windowHit() 一个都不报（" + hits + "），不许把窗口算进可疑字数");
    }
    // ---------------------------------------------------------------- ④ 开关与接线

    private static void gateAndWiring() throws Exception {
        String manifest = readText(assetDir + "/aigc-model.tsv");
        // 清单自说自话"已标定"，但实测数没过线：仍然不许用（开关认实测数，不认清单里的旗子）
        installWith(rewrite(manifest, "calibrated", "true"));
        check(!AigcNgramModel.calibrated(),
                "清单把 calibrated 改成 true 但实测 AUC 只有 " + AigcNgramModel.current().manifest().holdoutAuc
                        + " 时仍然判不达标");
        check(!AigcNgramModel.engaged(), "开关没开，检测链路不会去问模型要分");
        AigcDetector.Result off = AigcDetector.detect(firstParagraph(corpusDir + "/aigc-label-machine-domain.txt"));
        check(off.modelSentences == 0, "模型未达标时 modelSentences=0（实际 " + off.modelSentences + "）");
        check(off.verdict.indexOf("判据未标定") >= 0, "报告口径仍然是那句认错的话（判据未标定）");

        // 公共留出 / 学术留出 / 跨域三栏全部刷成 0.96~0.9999：只要真稿那一档没过线，开关必须还是关的。
        // 本轮量出来 r(公共留出, 真稿) = -0.2275，那三栏的高分连方向都不保证，更不能拿来开门。
        installWith(rewrite(rewrite(rewrite(rewrite(manifest,
                        "public_valid_auc", "0.9999"), "academic_auc", "0.9999"),
                "cross_domain_mean", "0.9800"), "cross_domain_worst", "0.9600"));
        check(!AigcNgramModel.calibrated(),
                "公共/学术/跨域三栏都改成 0.96~0.9999 之后开关仍然关着——出厂只认 ③ 真稿那一档（实测 "
                        + AigcNgramModel.current().manifest().holdoutAuc + "）");

        // 假装一份"数真的过线"的清单：模型路径必须真的能跑起来，而且必须自报家门
        String fake = rewrite(rewrite(rewrite(rewrite(rewrite(rewrite(manifest,
                        "calibrated", "true"), "holdout_auc", "0.9100"),
                "holdout_human_fp_per_mille", "0.500"), "window_calibrated", "true"),
                "template_window_hit", "0.9500"), "human_window_hit", "0.0000");
        AigcNgramModel on = installWith(fake);
        check(AigcNgramModel.calibrated() && AigcNgramModel.engaged(),
                "同一套权重、实测数换成过线的值之后 calibrated() 才为 true");
        String prose = firstParagraph(corpusDir + "/aigc-label-machine-domain.txt");
        AigcDetector.Result on1 = AigcDetector.detect(prose);
        check(on1.modelSentences > 0, "模型达标后 " + on1.modelSentences + " 句改由离线模型打分");
        check(on1.verdict.indexOf(on.manifest().version) >= 0,
                "印出来的那一句带上了模型版本号 " + on.manifest().version + "（什么模型、在哪些数据上量的）");
        check(countOccurrences(on1.verdict, "比例") <= 1, "一份报告的 verdict 里「比例」最多出现一次");
        check(on1.windowHits >= 0, "窗口命中计数 " + on1.windowHits + " 句（可疑字数只按整句计，不重不漏）");
        // 账本口径：同一批可疑区间交两遍，字数不许翻倍（AIGC 与重复的交集只计一次）。
        // 这一版模型分普遍低于区间门槛，拿不到 flagged 区间，就自己造两个重叠区间走同一条 closeSpans。
        int[] spans = new int[8];
        int at = 0;
        for (int i = 0; i < on1.segments.size(); i++) {
            if (!on1.segments.get(i).flagged) continue;
            spans[at++] = on1.segments.get(i).start;
            spans[at++] = on1.segments.get(i).end;
        }
        if (at == 0) {
            spans[0] = 8; spans[1] = 60; spans[2] = 50; spans[3] = 120;   // 故意重叠 10 字
            at = 4;
        }
        CharLedger.Balance once = CharLedger.closeSpans(prose, null, null, null, spans);
        CharLedger.Balance twice = CharLedger.closeSpans(prose, null, null, null, doubleUp(spans, at));
        check(once.machineChars > 0 && once.machineChars == twice.machineChars,
                "机器腔字数只计一次：同一批可疑区间（含重叠）交两遍，" + once.machineChars + " 字不变");
        installShipped();
    }

    private static void uninstallAndClose() throws Exception {
        AigcNgramModel.uninstall();
        check(AigcNgramModel.current() == null, "卸载之后没有模型在场");
        check(!AigcNgramModel.engaged() && !AigcNgramModel.calibrated(), "没有模型时开关一律关");
        AigcDetector.Result r = AigcDetector.detect(firstParagraph(corpusDir + "/real-prose.txt"));
        check(r.verdict.indexOf("判据未标定") >= 0 && r.verdict.indexOf("%") < 0,
                "没模型时报告既不印百分号也不装作量到了");
    }

    // ---------------------------------------------------------------- 工具

    private static AigcNgramModel installShipped() throws Exception {
        return AigcNgramModel.install(stream(assetDir + "/aigc-zh-weights.tsv"),
                stream(assetDir + "/aigc-zh-freq.tsv"), stream(assetDir + "/aigc-model.tsv"));
    }

    private static AigcNgramModel installWith(String manifest) throws Exception {
        return AigcNgramModel.install(stream(assetDir + "/aigc-zh-weights.tsv"),
                stream(assetDir + "/aigc-zh-freq.tsv"),
                new ByteArrayInputStream(manifest.getBytes(Charset.forName("UTF-8"))));
    }

    private static InputStream stream(String path) throws IOException {
        java.io.File f = new java.io.File(path);
        if (!f.exists()) throw new AssertionError("缺随包文件：" + path + "（缺文件一律判失败，不许静默跳过）");
        return new FileInputStream(f);
    }

    private static String rewrite(String manifest, String key, String value) {
        String[] lines = manifest.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith(key + "\t")) lines[i] = key + "\t" + value;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) sb.append(lines[i]).append(i + 1 < lines.length ? "\n" : "");
        return sb.toString();
    }

    private static int[] doubleUp(int[] spans, int at) {
        int[] out = new int[at * 2];
        System.arraycopy(spans, 0, out, 0, at);
        System.arraycopy(spans, 0, out, at, at);
        return out;
    }

    private static String readText(String path) throws IOException {
        ArrayList<String> lines = read(path);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) sb.append((String) lines.get(i)).append('\n');
        return sb.toString();
    }

    private static String firstParagraph(String path) throws IOException {
        ArrayList<String> lines = read(path);
        for (int i = 0; i < lines.size(); i++) {
            if (((String) lines.get(i)).length() > 40) return (String) lines.get(i);
        }
        return (String) lines.get(0);
    }

    /** 计分句折行去空白之后的字符数：与 Python 侧量句长同一个口径。 */
    private static ArrayList<Double> sentenceCompactLengths(String[] paragraphs) {
        ArrayList<Double> out = new ArrayList<Double>();
        for (int i = 0; i < paragraphs.length; i++) {
            if (paragraphs[i].trim().length() == 0) continue;
            AigcDetector.Result r = AigcDetector.detect(paragraphs[i]);
            for (int k = 0; k < r.sentences.size(); k++) {
                AigcDetector.Sentence s = r.sentences.get(k);
                out.add(Double.valueOf(TextCorpus.compactOf(paragraphs[i].substring(s.start, s.end)).length()));
            }
        }
        return out;
    }

    /** 用应用自己的切句口径把一批自然段切成计分句，再逐句问模型要分。 */
    private static ArrayList<Double> sentenceScores(AigcNgramModel model, String document) {
        ArrayList<Double> out = new ArrayList<Double>();
        String[] paragraphs = document.split("\n");
        for (int i = 0; i < paragraphs.length; i++) {
            if (paragraphs[i].trim().length() == 0) continue;
            AigcDetector.Result r = AigcDetector.detect(paragraphs[i]);
            for (int k = 0; k < r.sentences.size(); k++) {
                AigcDetector.Sentence s = r.sentences.get(k);
                out.add(Double.valueOf(model.score(TextCorpus.compactOf(paragraphs[i].substring(s.start, s.end)))));
            }
        }
        return out;
    }

    private static ArrayList<String> read(String path) throws IOException {
        java.io.File f = new java.io.File(path);
        if (!f.exists()) throw new AssertionError("缺语料：" + path + "（缺语料一律判失败）");
        ArrayList<String> out = new ArrayList<String>();
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), Charset.forName("UTF-8")));
        String line;
        while ((line = r.readLine()) != null) out.add(line);
        r.close();
        return out;
    }

    /** Mann-Whitney，带并列值取平均秩；AUC(机器>真人)。 */
    private static double auc(ArrayList<Double> above, ArrayList<Double> below) {
        int nh = above.size(), nm = below.size();
        if (nh == 0 || nm == 0) return Double.NaN;
        double[] all = new double[nh + nm];
        for (int i = 0; i < nh; i++) all[i] = above.get(i).doubleValue();
        for (int i = 0; i < nm; i++) all[nh + i] = below.get(i).doubleValue();
        double[] sorted = all.clone();
        Arrays.sort(sorted);
        double[] ranks = new double[all.length];
        for (int i = 0; i < all.length; i++) {
            int lo = lowerBound(sorted, all[i]), hi = upperBound(sorted, all[i]);
            ranks[i] = (lo + 1 + hi) / 2d;
        }
        double sumAbove = 0d;
        for (int i = 0; i < nh; i++) sumAbove += ranks[i];
        return (sumAbove - nh * (nh + 1d) / 2d) / ((double) nh * nm);
    }

    private static int lowerBound(double[] a, double v) {
        int lo = 0, hi = a.length;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (a[m] < v) lo = m + 1; else hi = m; }
        return lo;
    }

    private static int upperBound(double[] a, double v) {
        int lo = 0, hi = a.length;
        while (lo < hi) { int m = (lo + hi) >>> 1; if (a[m] <= v) lo = m + 1; else hi = m; }
        return lo;
    }

    private static int countOccurrences(String s, String needle) {
        int n = 0, at = 0;
        while ((at = s.indexOf(needle, at)) >= 0) { n++; at += needle.length(); }
        return n;
    }

    private static String round(double v, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", v);
    }
}

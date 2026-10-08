package com.rikkahub.wordlite;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;

/**
 * AIGC 系数拟合台（2026-10-08 标定轮）。只读语料与特征，不改产品代码：候选系数在内存里扫，
 * 落表由人手工写进 AigcScorer 并跑 tests/AigcFeatureAuditRegression 复验。
 *
 * 三条硬纪律（与 docs/aigc-corpus.md 第六节、docs/aigc-calibration.md 同一条口径）：
 * 1) 划分干净：只有 FIT 档参与拟合（M-RAW + H1/H2/H3）。M-EVADE、M-DOMAIN、X1、hard-human
 *    一律 HOLD，只读不训；拟合台上任何一步都不看 HOLD 的数，HOLD 只在最后被量一次。
 * 2) 形状不动：句分永远是 min(1, Σ_组 max(取值 x 组内系数) x 混排折扣)，系数一律非负
 *    （AigcScorer.Coefficients.set 本来就拒绝负系数）。方向为负的证据只能置 0，不许改架构出负权。
 * 3) 静态纪律照旧：组系数加和 <= 1.0，单条系数 < SEGMENT_FLAG_GATE（任何单条证据不足以单独成案）。
 *
 * 用法：
 *   java com.rikkahub.wordlite.AigcCalibrationFit screen   逐特征方向表（FIT / HOLD 两侧各量一次）
 *   java com.rikkahub.wordlite.AigcCalibrationFit fit      坐标上升拟合 + 留出档验收
 */
public final class AigcCalibrationFit {
    private static final class Tier {
        final String code, path;
        final boolean human, holdout;
        final int minChars;
        Tier(String code, String path, boolean human, boolean holdout, int minChars) {
            this.code = code; this.path = path; this.human = human; this.holdout = holdout; this.minChars = minChars;
        }
    }

    /** FIT：M-RAW + H1/H2/H3。HOLD：M-EVADE + M-DOMAIN + X1 + hard-human（钉子户单句，不设 120 字门槛）。 */
    private static final Tier[] TIERS = {
        new Tier("H1", "tests/corpus/real-prose.txt", true, false, 120),
        new Tier("H2", "tests/corpus/aigc-label-human-verbatim.txt", true, false, 120),
        new Tier("H3", "tests/corpus/cnki-cross.txt", true, false, 120),
        new Tier("M-RAW", "tests/corpus/aigc-label-machine-raw.txt", false, false, 120),
        new Tier("M-EVADE", "tests/corpus/aigc-label-machine-evasive.txt", false, true, 120),
        new Tier("M-DOMAIN", "tests/corpus/aigc-label-machine-domain.txt", false, true, 120),
        new Tier("X1", "tests/corpus/aigc-label-human-polish-agent.txt", true, true, 120),
        new Tier("HARD-H", "tests/corpus/aigc-hard-human.txt", true, true, 0),
    };

    private static final class Row {
        final double[] value = new double[AigcFeatureId.COUNT];
        double chars;
        int family;
        String tier;
        int docSentences;     // 这一段里有几个计分句：文档级特征要有 5 句才可能亮灯，长度不齐就是假分离
    }

    private static final LinkedHashMap<String, ArrayList<Row>> BY_TIER = new LinkedHashMap<String, ArrayList<Row>>();
    private static final LinkedHashMap<String, Tier> TIER_BY_CODE = new LinkedHashMap<String, Tier>();
    static {
        for (int i = 0; i < TIERS.length; i++) TIER_BY_CODE.put(TIERS[i].code, TIERS[i]);
    }

    private static void scan(String text, ArrayList<Row> into, String tier) {
        String norm = TextCorpus.normalize(text);
        ArrayList<int[]> spans = TextCorpus.sentences(text);
        ArrayList<AigcFeatures.Segment> scored = new ArrayList<AigcFeatures.Segment>();
        for (int i = 0; i < spans.size(); i++) {
            AigcFeatures.Segment seg = AigcFeatures.segment(norm, spans.get(i)[0], spans.get(i)[1]);
            if (!AigcFeatures.scores(seg)) continue;
            scored.add(seg);
        }
        AigcFeatures.DocStats stats = AigcFeatures.stats(norm, spans, scored);
        AigcFeatureId[] ids = AigcFeatureId.values();
        for (int i = 0; i < scored.size(); i++) {
            AigcFeatures.Segment seg = scored.get(i);
            Row row = new Row();
            row.chars = seg.validChars;
            row.family = seg.family;
            row.tier = tier;
            row.docSentences = scored.size();
            for (int k = 0; k < ids.length; k++) row.value[k] = AigcFeatures.value(ids[k], seg, stats);
            into.add(row);
        }
    }

    private static void load() throws Exception {
        for (int t = 0; t < TIERS.length; t++) {
            Tier tier = TIERS[t];
            File f = new File(tier.path);
            if (!f.isFile()) throw new AssertionError("语料缺失：" + tier.path + "（" + tier.code + "）");
            ArrayList<Row> rows = new ArrayList<Row>();
            BufferedReader read = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), Charset.forName("UTF-8")));
            String line;
            int kept = 0, skipped = 0;
            while ((line = read.readLine()) != null) {
                String text = line.trim();
                if (text.isEmpty() || text.startsWith("#")) continue;
                if (TextCorpus.normalize(text).length() < tier.minChars) { skipped++; continue; }
                scan(text, rows, tier.code);
                kept++;
            }
            read.close();
            BY_TIER.put(tier.code, rows);
            System.out.println("LOAD " + pad(tier.code, 10) + (tier.holdout ? "HOLD " : "FIT  ")
                    + (tier.human ? "HUMAN  " : "MACHINE") + " paras=" + pad(String.valueOf(kept), 4)
                    + " dropped<min=" + skipped + " sentences=" + rows.size());
        }
    }

    // ---- 打分（与 AigcScorer.score 同式，逐位校验见 verify） ----

    private static double score(Row row, double[] w) {
        double[] best = new double[AigcFeatureId.GROUP_COUNT];
        AigcFeatureId[] ids = AigcFeatureId.values();
        for (int i = 0; i < ids.length; i++) {
            double v = row.value[i];
            if (v <= 0d || w[i] <= 0d) continue;
            int g = AigcFeatureId.group(ids[i]);
            double contribution = v * w[i];
            if (contribution > best[g]) best[g] = contribution;
        }
        double total = 0d;
        for (int i = 0; i < best.length; i++) total += best[i];
        if (row.family == AigcFamily.MIXED) total *= AigcFamily.MIXED_DISCOUNT;
        return total > 1d ? 1d : (total < 0d ? 0d : total);
    }

    /** 与产品路径逐位校验：harness 重算的分必须等于 AigcScorer.score(AigcFeatures.of(...)) 的分。 */
    private static int verify(ArrayList<Row> rows, double[] w) {
        int checked = 0;
        AigcFeatureId[] ids = AigcFeatureId.values();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            double mine = score(row, w);
            double theirs;
            // 与 AigcFeatures.of 同口径：取值 > 0 的进 Hit，取值一律 clamp 到 1。
            double[] best = new double[AigcFeatureId.GROUP_COUNT];
            for (int k = 0; k < ids.length; k++) {
                double v = row.value[k];
                if (v <= 0d) continue;
                int g = AigcFeatureId.group(ids[k]);
                double contribution = (v > 1d ? 1d : v) * w[k];
                if (contribution > best[g]) best[g] = contribution;
            }
            double total = 0d;
            for (int g = 0; g < best.length; g++) total += best[g];
            AigcScorer.Coefficients table = new AigcScorer.Coefficients(row.family,
                    row.family == AigcFamily.MIXED ? AigcFamily.MIXED_DISCOUNT : 1.00d, "verify");
            for (int k = 0; k < ids.length; k++) table.set(ids[k], w[k]);
            theirs = AigcScorer.score(hitsOf(row), table);
            if (Math.abs(mine - theirs) > 1e-12d)
                throw new AssertionError("打分与 AigcScorer 不一致：" + mine + " vs " + theirs);
            checked++;
        }
        return checked;
    }

    private static ArrayList<AigcFeatures.Hit> hitsOf(Row row) {
        ArrayList<AigcFeatures.Hit> hits = new ArrayList<AigcFeatures.Hit>();
        AigcFeatureId[] ids = AigcFeatureId.values();
        for (int i = 0; i < ids.length; i++) {
            if (row.value[i] <= 0d) continue;
            AigcFeatures.Hit hit = new AigcFeatures.Hit(ids[i]);
            hit.value = row.value[i] > 1d ? 1d : row.value[i];
            hits.add(hit);
        }
        return hits;
    }

    // ---- 统计量 ----

    private static double auc(double[] a, double[] b) {
        double[] sorted = b.clone();
        Arrays.sort(sorted);
        double wins = 0d;
        for (int i = 0; i < a.length; i++) {
            int lower = lowerBound(sorted, a[i]);      // < a[i]
            int upper = upperBound(sorted, a[i]);      // <= a[i]
            wins += lower + (upper - lower) * 0.5d;
        }
        return (a.length == 0 || b.length == 0) ? 0d : wins / ((double) a.length * (double) b.length);
    }

    private static int lowerBound(double[] v, double x) {
        int lo = 0, hi = v.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (v[mid] < x) lo = mid + 1; else hi = mid; }
        return lo;
    }

    private static int upperBound(double[] v, double x) {
        int lo = 0, hi = v.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (v[mid] <= x) lo = mid + 1; else hi = mid; }
        return lo;
    }

    private static double[] scoresOf(ArrayList<Row> rows, double[] w) {
        double[] out = new double[rows.size()];
        for (int i = 0; i < out.length; i++) out[i] = score(rows.get(i), w);
        return out;
    }

    private static double[] valuesOf(ArrayList<Row> rows, int k) {
        double[] out = new double[rows.size()];
        for (int i = 0; i < out.length; i++) out[i] = rows.get(i).value[k];
        return out;
    }

    private static ArrayList<Row> pool(boolean human, boolean holdout) {
        ArrayList<Row> out = new ArrayList<Row>();
        for (int t = 0; t < TIERS.length; t++) {
            if (TIERS[t].human != human || TIERS[t].holdout != holdout) continue;
            out.addAll(BY_TIER.get(TIERS[t].code));
        }
        return out;
    }

    private static ArrayList<Row> tier(String code) {
        ArrayList<Row> out = new ArrayList<Row>();
        ArrayList<Row> rows = BY_TIER.get(code);
        if (rows != null) out.addAll(rows);
        return out;
    }

    private static String quantiles(double[] v, double gate) {
        double[] sorted = v.clone();
        Arrays.sort(sorted);
        StringBuilder out = new StringBuilder();
        out.append("mean=").append(fmt3(mean(sorted)))
                .append(" p50=").append(fmt3(at(sorted, 0.50)))
                .append(" p90=").append(fmt3(at(sorted, 0.90)))
                .append(" p99=").append(fmt3(at(sorted, 0.99)))
                .append(" max=").append(sorted.length == 0 ? "-" : fmt3(sorted[sorted.length - 1]))
                .append(" over=").append(countOver(sorted, gate)).append('/').append(sorted.length)
                .append(" over1000=").append(fmt1(sorted.length == 0 ? 0d
                        : 1000d * countOver(sorted, gate) / sorted.length));
        return out.toString();
    }

    private static int countOver(double[] sorted, double gate) {
        int n = 0;
        for (int i = 0; i < sorted.length; i++) if (sorted[i] >= gate) n++;
        return n;
    }

    private static double at(double[] sorted, double q) {
        if (sorted.length == 0) return 0d;
        int i = (int) Math.round(q * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }

    private static double mean(double[] v) {
        double s = 0d;
        for (int i = 0; i < v.length; i++) s += v[i];
        return v.length == 0 ? 0d : s / v.length;
    }

    private static double fireRate(double[] v) {
        int n = 0;
        for (int i = 0; i < v.length; i++) if (v[i] > 0d) n++;
        return v.length == 0d ? 0d : (double) n / (double) v.length;
    }

    private static String fmt3(double v) { return String.format(java.util.Locale.US, "%.3f", Double.valueOf(v)); }
    private static String fmt6(double v) { return String.format(java.util.Locale.US, "%.6f", Double.valueOf(v)); }

    private static String fmt2(double v) { return String.format(java.util.Locale.US, "%.2f", Double.valueOf(v)); }
    private static String fmt1(double v) { return String.format(java.util.Locale.US, "%.1f", Double.valueOf(v)); }
    private static String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }

    // ---- 逐特征方向表 ----

    private static void screen() {
        ArrayList<Row> fitH = pool(true, false), fitM = pool(false, false);
        ArrayList<Row> holdH = pool(true, true), holdM = pool(false, true);
        AigcFeatureId[] ids = AigcFeatureId.values();
        System.out.println("\n# 逐特征方向（AUC(机器>真人)：>0.5 方向正；fit 侧用来筛掉方向为负的证据，hold 侧只读）");
        System.out.println("feature\tgroup\tpool\tmeanH\tmeanM\tfireH\tfireM\tAUC");
        for (int k = 0; k < ids.length; k++) {
            String[] pools = {"fit", "hold"};
            for (int t = 0; t < 2; t++) {
                ArrayList<Row> h = t == 0 ? fitH : holdH;
                ArrayList<Row> m = t == 0 ? fitM : holdM;
                double[] hv = valuesOf(h, k), mv = valuesOf(m, k);
                System.out.println(ids[k].name() + "\t" + AigcFeatureId.groupLabel(ids[k]) + "\t" + pools[t]
                        + "\t" + fmt3(mean(hv)) + "\t" + fmt3(mean(mv)) + "\t" + fmt3(fireRate(hv))
                        + "\t" + fmt3(fireRate(mv)) + "\t" + fmt3(auc(mv, hv)));
            }
        }
    }

    // ---- 拟合 ----

    private static final double GATE = AigcDetector.SEGMENT_FLAG_GATE;
    private static final double FP_BUDGET_PER_1000 = 2d;
    private static final double[] GRID = {0d, 0.05d, 0.10d, 0.15d, 0.20d, 0.25d, 0.30d, 0.35d, 0.40d};

    /** 静态纪律：组系数加和 <= 1.0；单条 < 门槛。与 AigcScorer.check 同两条。 */
    private static boolean legal(double[] w) {
        double[] group = new double[AigcFeatureId.GROUP_COUNT];
        AigcFeatureId[] ids = AigcFeatureId.values();
        for (int i = 0; i < ids.length; i++) {
            if (w[i] >= GATE) return false;
            int g = AigcFeatureId.group(ids[i]);
            if (w[i] > group[g]) group[g] = w[i];
        }
        double total = 0d;
        for (int i = 0; i < group.length; i++) total += group[i];
        return total <= 1.0d + 1e-9d;
    }

    private static double objective(double[] w, ArrayList<Row> fitH, ArrayList<Row> fitM, double maxHumanOver) {
        double[] hs = scoresOf(fitH, w);
        int over = countOver(hs, GATE);
        if (over > maxHumanOver) return -1d;
        double[] ms = scoresOf(fitM, w);
        int nonZero = 0;
        for (int i = 0; i < w.length; i++) if (w[i] > 0d) nonZero++;
        return auc(ms, hs) - 0.002d * nonZero;
    }

    private static void fit() throws Exception {
        ArrayList<Row> fitH = pool(true, false), fitM = pool(false, false);
        ArrayList<Row> holdH = pool(true, true), holdM = pool(false, true);
        double maxHumanOver = Math.floor(FP_BUDGET_PER_1000 / 1000d * fitH.size());
        AigcFeatureId[] ids = AigcFeatureId.values();

        // 方向筛：只看 FIT 侧。FIT 侧 AUC < 0.5（机器反而更低）的证据一律不许配系数——负系数本来就被
        // Coefficients.set 拒绝，这里把它写成"置 0"，与 docs/aigc-corpus.md 第九节那条结论一致。
        boolean[] allowed = new boolean[ids.length];
        System.out.println("\n# 方向筛（FIT 侧；HOLD 侧一栏只是同一件事的另一份读数，不参与任何决策）");
        System.out.println("feature\tfitAUC\tallowed\treason");
        for (int k = 0; k < ids.length; k++) {
            double[] hv = valuesOf(fitH, k), mv = valuesOf(fitM, k);
            double a = auc(mv, hv);
            boolean fire = fireRate(hv) + fireRate(mv) > 0d;
            String reason = "方向为正，进候选";
            if (!fire) { allowed[k] = false; reason = "FIT 两侧都不触发，无语料可审"; }
            else if (a < 0.5d) { allowed[k] = false; reason = "FIT 侧方向为反，只能置 0"; }
            else allowed[k] = a >= 0.5d;
            System.out.println(ids[k].name() + "\t" + fmt3(a) + "\t" + allowed[k] + "\t" + reason);
        }

        double[] w = new double[ids.length];
        ArrayList<Row> allFitH = fitH;
        double best = objective(w, allFitH, fitM, maxHumanOver);
        for (int pass = 0; pass < 8; pass++) {
            boolean improved = false;
            for (int k = 0; k < ids.length; k++) {
                if (!allowed[k]) continue;
                double keep = w[k];
                for (int g = 0; g < GRID.length; g++) {
                    if (GRID[g] == keep) continue;
                    double[] trial = w.clone();
                    trial[k] = GRID[g];
                    if (!legal(trial)) continue;
                    double value = objective(trial, allFitH, fitM, maxHumanOver);
                    if (value > best + 1e-12d) { best = value; w = trial; keep = GRID[g]; improved = true; }
                }
                w[k] = keep;
            }
            if (!improved) break;
        }

        int checked = verify(fitH, w) + verify(fitM, w) + verify(holdH, w) + verify(holdM, w);
        System.out.println("\n# 拟合结果（目标 = FIT 侧 AUC(机器>真人) - 0.002 x 非零系数条数，"
                + "约束 = FIT 真人侧 >=0.45 的句子数 <= " + (int) maxHumanOver + "）");
        System.out.println("FIT  sentences human=" + fitH.size() + " machine=" + fitM.size()
                + "  maxHumanOver=" + (int) maxHumanOver + "  verify-vs-AigcScorer=" + checked + " rows equal");
        for (int k = 0; k < ids.length; k++) {
            System.out.println("COEF " + pad(ids[k].name(), 18) + "group=" + AigcFeatureId.groupLabel(ids[k])
                    + " weight=" + fmt2(w[k]) + (allowed[k] ? "" : "  (方向不符或无语料，强制 0)"));
        }
        report(w, "候选表");
        for (double gate = 0.30d; gate <= 0.60d + 1e-9d; gate += 0.05d) {
            double[] hs = allScores(true, w);
            int over = countOver(hs, gate);
            System.out.println("GATE " + fmt2(gate) + " all-human-over=" + over + "/" + hs.length
                    + " per1000=" + fmt1(1000d * over / hs.length));
        }
    }

    private static double[] allScores(boolean human, double[] w) {
        double[] out = new double[0];
        ArrayList<Double> list = new ArrayList<Double>();
        for (int t = 0; t < TIERS.length; t++) {
            if (TIERS[t].human != human) continue;
            ArrayList<Row> rows = BY_TIER.get(TIERS[t].code);
            for (int i = 0; i < rows.size(); i++) list.add(Double.valueOf(score(rows.get(i), w)));
        }
        out = new double[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i).doubleValue();
        return out;
    }

    /** 全部档位的分布 + 三组 AUC（FIT / HOLD / 合并）。 */
    private static void report(double[] w, String label) throws Exception {
        System.out.println("\n# " + label + " 的逐档分布（门槛 " + fmt3(GATE) + "）");
        System.out.println("tier\tpool\tside\tsentences\tdistribution");
        for (int t = 0; t < TIERS.length; t++) {
            ArrayList<Row> rows = BY_TIER.get(TIERS[t].code);
            System.out.println(pad(TIERS[t].code, 10) + "\t" + (TIERS[t].holdout ? "HOLD" : "FIT ") + "\t"
                    + (TIERS[t].human ? "HUMAN  " : "MACHINE") + "\t" + rows.size() + "\t"
                    + quantiles(scoresOf(rows, w), GATE));
        }
        double[] fitH = scoresOf(pool(true, false), w), fitM = scoresOf(pool(false, false), w);
        double[] holdH = scoresOf(pool(true, true), w), holdM = scoresOf(pool(false, true), w);
        System.out.println("AUC  FIT  (机器>真人)=" + fmt3(auc(fitM, fitH)) + "  n=" + fitM.length + "v" + fitH.length);
        System.out.println("AUC  HOLD(机器>真人)=" + fmt3(auc(holdM, holdH)) + "  n=" + holdM.length + "v" + holdH.length
                    + "  exact=" + String.format(java.util.Locale.US, "%.6f", Double.valueOf(auc(holdM, holdH)))
                    + "  docauc-hold=" + fmt3(auc(pooledDocScores(false, true, w), pooledDocScores(true, true, w))));
        ArrayList<Row> allH = new ArrayList<Row>(); allH.addAll(pool(true, false)); allH.addAll(pool(true, true));
        ArrayList<Row> allM = new ArrayList<Row>(); allM.addAll(pool(false, false)); allM.addAll(pool(false, true));
        System.out.println("AUC  ALL (机器>真人)=" + fmt3(auc(scoresOf(allM, w), scoresOf(allH, w))));
        System.out.println("# 逐对（同一张表，逐档两两）");
        for (int m = 0; m < TIERS.length; m++) {
            if (TIERS[m].human) continue;
            for (int h = 0; h < TIERS.length; h++) {
                if (!TIERS[h].human) continue;
                System.out.println("PAIR " + pad(TIERS[m].code, 10) + " vs " + pad(TIERS[h].code, 10)
                        + " pool=" + (TIERS[m].holdout || TIERS[h].holdout ? "HOLD混" : "FIT")
                        + " AUC=" + fmt3(auc(scoresOf(tier(TIERS[m].code), w), scoresOf(tier(TIERS[h].code), w))));
            }
        }
        docAuc(w);
        System.out.println("EXT  HOLD 机器最低分=" + fmt3(min(scoresOf(pool(false, true), w)))
                + " HOLD 真人最高分=" + fmt3(max(scoresOf(pool(true, true), w)))
                + "  ALL 真人最高分=" + fmt3(max(scoresOf(allH, w))));
    }

    /** 段级 AUC：把每段的字符加权均分当一个样本。文档级特征在句级 AUC 上会被同段重复计数放大，这一栏是刹车。 */
    private static void docAuc(double[] w) throws Exception {
        System.out.println("# 段级（每段一个样本，字符加权均分）AUC：句级 AUC 会把文档级特征在同段内重复计数，这一栏才是刹车");
        for (int t = 0; t < TIERS.length; t++) {
            if (TIERS[t].human) continue;
            for (int h = 0; h < TIERS.length; h++) {
                if (!TIERS[h].human) continue;
                System.out.println("DOCAUC " + pad(TIERS[t].code, 10) + " vs " + pad(TIERS[h].code, 10)
                        + " pool=" + (TIERS[t].holdout || TIERS[h].holdout ? "HOLD混" : "FIT")
                        + " AUC=" + fmt3(auc(docScores(TIERS[t].code, w), docScores(TIERS[h].code, w))));
            }
        }
        System.out.println("DOCAUC HOLD合 vs HOLD合=" + fmt3(auc(pooledDocScores(false, true, w), pooledDocScores(true, true, w)))
                + "  FIT合 vs FIT合=" + fmt3(auc(pooledDocScores(false, false, w), pooledDocScores(true, false, w))));
    }

    private static double[] docScores(String code, double[] w) throws Exception {
        ArrayList<Double> list = new ArrayList<Double>();
        for (String line : paragraphs(TIER_BY_CODE.get(code))) {
            list.add(Double.valueOf(docScore(line, w)));
        }
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i).doubleValue();
        return out;
    }

    private static double[] pooledDocScores(boolean human, boolean holdout, double[] w) throws Exception {
        ArrayList<Double> list = new ArrayList<Double>();
        for (int t = 0; t < TIERS.length; t++) {
            if (TIERS[t].human != human || TIERS[t].holdout != holdout) continue;
            for (String line : paragraphs(TIERS[t])) list.add(Double.valueOf(docScore(line, w)));
        }
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i).doubleValue();
        return out;
    }

    /** 一段的字符加权均分：与 AigcDetector.Result.rate 同口径（除 100）。 */
    private static double docScore(String text, double[] w) {
        String norm = TextCorpus.normalize(text);
        ArrayList<int[]> spans = TextCorpus.sentences(text);
        ArrayList<AigcFeatures.Segment> scored = new ArrayList<AigcFeatures.Segment>();
        for (int i = 0; i < spans.size(); i++) {
            AigcFeatures.Segment seg = AigcFeatures.segment(norm, spans.get(i)[0], spans.get(i)[1]);
            if (AigcFeatures.scores(seg)) scored.add(seg);
        }
        AigcFeatures.DocStats stats = AigcFeatures.stats(norm, spans, scored);
        AigcFeatureId[] ids = AigcFeatureId.values();
        double weighted = 0d, chars = 0d;
        for (int i = 0; i < scored.size(); i++) {
            AigcFeatures.Segment seg = scored.get(i);
            Row row = new Row();
            row.chars = seg.validChars;
            row.family = seg.family;
            for (int k = 0; k < ids.length; k++) row.value[k] = AigcFeatures.value(ids[k], seg, stats);
            double s = score(row, w);
            weighted += s * seg.validChars;
            chars += seg.validChars;
        }
        return chars <= 0d ? 0d : weighted / chars;
    }

    private static double min(double[] v) {
        double m = Double.POSITIVE_INFINITY;
        for (int i = 0; i < v.length; i++) if (v[i] < m) m = v[i];
        return v.length == 0 ? 0d : m;
    }

    private static double max(double[] v) {
        double m = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < v.length; i++) if (v[i] > m) m = v[i];
        return v.length == 0 ? 0d : m;
    }

    /**
     * 审计口径：逐字照 tests/AigcFeatureAuditRegression 的七档池（H1/H2/H3/X1 + M-RAW/M-EVADE/M-DOMAIN，
     * 不含钉子户 HARD-H），产品表，一次打出 AUC / 两侧最高分 / 真人误报率。
     * AigcScorer.UNCALIBRATED_NOTE 里那几个数就是从这里抄的，那个套件会把它和自己现算的数对着断言。
     */
    private static void auditPool() {
        AigcFeatureId[] ids = AigcFeatureId.values();
        double[] w = new double[ids.length];
        for (int i = 0; i < ids.length; i++) w[i] = AigcScorer.defaults(AigcFamily.CHINESE).get(ids[i]);
        ArrayList<Row> h = new ArrayList<Row>(), m = new ArrayList<Row>();
        String[] humanCodes = {"H1", "H2", "H3", "X1"};
        String[] machineCodes = {"M-RAW", "M-EVADE", "M-DOMAIN"};
        for (int i = 0; i < humanCodes.length; i++) h.addAll(tier(humanCodes[i]));
        for (int i = 0; i < machineCodes.length; i++) m.addAll(tier(machineCodes[i]));
        double[] hs = scoresOf(h, w), ms = scoresOf(m, w);
        int fp = countOver(hs, GATE);
        System.out.println("AUDITPOOL human=" + hs.length + " machine=" + ms.length
                + " AUC=" + fmt3(auc(ms, hs)) + " humanMax=" + fmt3(max(hs)) + " machineMax=" + fmt3(max(ms))
                + " humanOver=" + fp + " per1000=" + fmt2(1000d * fp / hs.length));
        int flipped = 0, dead = 0, verified = 0;
        StringBuilder vnames = new StringBuilder(), dnames = new StringBuilder(), fnames = new StringBuilder();
        for (int k = 0; k < ids.length; k++) {
            double[] hv = valuesOf(h, k), mv = valuesOf(m, k);
            double a = auc(mv, hv);
            double fh = fireRate(hv), fm = fireRate(mv);
            if (a < 0.45d) { flipped++; if (fnames.length() > 0) fnames.append('+'); fnames.append(ids[k].name()); }
            if (fh + fm <= 0d) { dead++; if (dnames.length() > 0) dnames.append('+'); dnames.append(ids[k].name()); }
            if (a >= 0.55d && fh > 0d && fm > 0d) {
                verified++;
                if (vnames.length() > 0) vnames.append('+');
                vnames.append(ids[k].name());
            }
            System.out.println("AUDITFEAT " + pad(ids[k].name(), 19) + " group=" + AigcFeatureId.groupLabel(ids[k])
                    + " meanH=" + fmt3(mean(hv)) + " meanM=" + fmt3(mean(mv)) + " medH=" + fmt3(median(hv))
                    + " medM=" + fmt3(median(mv)) + " AUC=" + fmt3(a) + " fireH=" + fmt3(fh)
                    + " fireM=" + fmt3(fm));
        }
        System.out.println("AUDITCOUNT flipped=" + flipped + " [" + fnames + "]");
        System.out.println("AUDITCOUNT dead=" + dead + " [" + dnames + "]");
        System.out.println("AUDITCOUNT verified=" + verified + " [" + vnames + "] of " + ids.length);
    }

    private static double median(double[] v) {
        double[] c = v.clone();
        Arrays.sort(c);
        if (c.length == 0) return 0d;
        return c.length % 2 == 1 ? c[c.length / 2] : (c[c.length / 2 - 1] + c[c.length / 2]) / 2d;
    }

    /**
     * 天花板实验：在 AigcScorer.check 那两条静态纪律之内（单条 < 门槛、组加和 ≤ 1.0），
     * 尽最大努力让机器句过线，同时要求 FIT 真人侧一句都不许过线。
     * 这一台回答的是"这套特征到底能不能出数"，不是"哪张表最好看"：过不了这一关，换号就没有资格。
     */
    private static void ceiling() throws Exception {
        AigcFeatureId[] ids = AigcFeatureId.values();
        ArrayList<Row> fitH = pool(true, false), fitM = pool(false, false);
        ArrayList<Row> holdH = pool(true, true), holdM = pool(false, true);
        boolean[] allowed = new boolean[ids.length];
        for (int k = 0; k < ids.length; k++) {
            allowed[k] = auc(valuesOf(fitM, k), valuesOf(fitH, k)) >= 0.5d
                    && fireRate(valuesOf(fitH, k)) + fireRate(valuesOf(fitM, k)) > 0d;
        }
        double[] w = new double[ids.length];
        double best = -1d;
        double[] grid = {0d, 0.05d, 0.10d, 0.15d, 0.20d, 0.25d, 0.30d, 0.35d, 0.40d};
        for (int pass = 0; pass < 8; pass++) {
            boolean improved = false;
            for (int k = 0; k < ids.length; k++) {
                if (!allowed[k]) continue;
                for (int g = 0; g < grid.length; g++) {
                    double[] trial = w.clone();
                    trial[k] = grid[g];
                    if (!legal(trial)) continue;
                    double[] hs = scoresOf(fitH, trial);
                    if (countOver(hs, GATE) > 0) continue;             // FIT 真人侧一句都不许过线
                    double[] ms = scoresOf(fitM, trial);
                    double obj = overFraction(ms, GATE) * 100d + mean(ms) * 10d;
                    if (obj > best + 1e-12d) { best = obj; w = trial; improved = true; }
                }
            }
            if (!improved) break;
        }
        System.out.println("\n# 天花板实验：目标 = FIT 机器句过 " + fmt2(GATE) + " 的比例（+均分权重），"
                + "硬约束 = FIT 真人句一句都不许过线，静态纪律照旧");
        for (int k = 0; k < ids.length; k++) {
            if (w[k] <= 0d) continue;
            System.out.println("CEILCOEF " + pad(ids[k].name(), 19) + " weight=" + fmt2(w[k]));
        }
        report(w, "天花板表");
        double[] fh = scoresOf(fitH, w), fm = scoresOf(fitM, w);
        double[] hh = scoresOf(holdH, w), hm = scoresOf(holdM, w);
        System.out.println("CEILING FIT  machine-over=" + countOver(fm, GATE) + "/" + fm.length
                + " human-over=" + countOver(fh, GATE) + "/" + fh.length
                + " machineMax=" + fmt3(max(fm)) + " humanMax=" + fmt3(max(fh)));
        System.out.println("CEILING HOLD machine-over=" + countOver(hm, GATE) + "/" + hm.length
                + " human-over=" + countOver(hh, GATE) + "/" + hh.length
                + " machineMax=" + fmt3(max(hm)) + " humanMax=" + fmt3(max(hh))
                + " AUC=" + fmt3(auc(hm, hh)));
        String[] probes = {"SHAPE_REPEAT=0.40", "SHAPE_REPEAT=0.40;OPENING_ECHO=0.40",
                "SHAPE_REPEAT=0.40;OPENING_ECHO=0.40;SENT_LEN_SYMMETRY=0.15",
                "SHAPE_REPEAT=0.40;BURST=0.40;OPENING_ECHO=0.15"};
        for (int i = 0; i < probes.length; i++) probe(probes[i], fitH, fitM, holdH, holdM, ids);
    }

    private static double overFraction(double[] v, double gate) {
        return v.length == 0 ? 0d : (double) countOver(v, gate) / (double) v.length;
    }

    private static void probe(String spec, ArrayList<Row> fitH, ArrayList<Row> fitM,
                              ArrayList<Row> holdH, ArrayList<Row> holdM, AigcFeatureId[] ids) {
        double[] w = new double[ids.length];
        for (String term : spec.split(";")) {
            String[] kv = term.split("=");
            for (int k = 0; k < ids.length; k++) {
                if (ids[k].name().equals(kv[0].trim())) w[k] = Double.parseDouble(kv[1].trim());
            }
        }
        boolean legal = legal(w);
        double[] fh = scoresOf(fitH, w), fm = scoresOf(fitM, w);
        double[] hh = scoresOf(holdH, w), hm = scoresOf(holdM, w);
        System.out.println("PROBE [" + spec + "] legal=" + legal
                + " | FIT m-over=" + countOver(fm, GATE) + "/" + fm.length + " h-over=" + countOver(fh, GATE)
                + "/" + fh.length + " mMax=" + fmt3(max(fm)) + " hMax=" + fmt3(max(fh))
                + " | HOLD m-over=" + countOver(hm, GATE) + "/" + hm.length + " h-over=" + countOver(hh, GATE)
                + "/" + hh.length + " hMax=" + fmt3(max(hh))
                + " | 真人误报/千句(全 8 档)=" + fmt2(1000d * (countOver(fh, GATE) + countOver(hh, GATE))
                        / (fh.length + hh.length)));
    }

    /**
     * 长度对齐诊断：文档级特征要 ≥5 个计分句才可能亮灯，而 M-RAW 平均 6.6 句、H1 平均 3.7 句，
     * 直接量 AUC 会把"段落更长"误读成"更像机器"。这一栏只留两侧都 ≥5 句的段落，把地板拆掉再看方向。
     */
    private static void matched() {
        AigcFeatureId[] ids = AigcFeatureId.values();
        String[] pools = {"fit", "hold"};
        System.out.println("\n# 长度对齐后的逐特征方向（两侧都只留计分句 >= 5 的段落；括号里是原来的全量 AUC）");
        System.out.println("feature\tpool\tnH\tnM\tfireH\tfireM\tAUC-matched\tAUC-all");
        for (int k = 0; k < ids.length; k++) {
            double[] hvAll = valuesOf(pool(true, false), k), mvAll = valuesOf(pool(false, false), k);
            for (int t = 0; t < 2; t++) {
                ArrayList<Row> h = filterLong(pool(t == 0 ? true : true, t == 0 ? false : true), true);
                ArrayList<Row> m = filterLong(pool(false, t == 0 ? false : true), true);
                double[] hv = valuesOf(h, k), mv = valuesOf(m, k);
                double all = t == 0 ? auc(mvAll, hvAll)
                        : auc(valuesOf(pool(false, true), k), valuesOf(pool(true, true), k));
                if (fireRate(hv) + fireRate(mv) <= 0d && fireRate(hvAll) + fireRate(mvAll) <= 0d) continue;
                System.out.println(ids[k].name() + "\t" + pools[t] + "\t" + h.size() + "\t" + m.size() + "\t"
                        + fmt3(fireRate(hv)) + "\t" + fmt3(fireRate(mv)) + "\t" + fmt3(auc(mv, hv))
                        + "\t" + fmt3(all));
            }
        }
    }

    private static ArrayList<Row> filterLong(ArrayList<Row> rows, boolean keep) {
        ArrayList<Row> out = new ArrayList<Row>();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).docSentences >= AigcFeatures.MIN_DOC_SHAPE_SENTENCES) out.add(rows.get(i));
        }
        return out;
    }

    /** 产品表（v1-order-only）在这批语料上的全部对外可引用数字，一次打全，供 docs 与断言消息取数。 */
    private static void shipped() throws Exception {
        double[] w = new double[AigcFeatureId.COUNT];
        AigcFeatureId[] ids = AigcFeatureId.values();
        for (int i = 0; i < ids.length; i++) w[i] = AigcScorer.defaults(AigcFamily.CHINESE).get(ids[i]);
        System.out.println("\n# 产品表 " + AigcScorer.defaults(AigcFamily.CHINESE).version()
                + "（组系数加和与单条上限由 AigcScorer.check 守着）");
        report(w, "产品表 v1-order-only");
        ArrayList<Row> allH = new ArrayList<Row>(); allH.addAll(pool(true, false)); allH.addAll(pool(true, true));
        ArrayList<Row> allM = new ArrayList<Row>(); allM.addAll(pool(false, false)); allM.addAll(pool(false, true));
        double[] hs = scoresOf(allH, w), ms = scoresOf(allM, w);
        int fp = countOver(hs, GATE);
        System.out.println("SHIPPED human=" + hs.length + " machine=" + ms.length
                + " AUC=" + fmt3(auc(ms, hs)) + " humanMax=" + fmt3(max(hs)) + " machineMax=" + fmt3(max(ms))
                + " humanFP(>=" + fmt2(GATE) + ")=" + fp + " per1000=" + fmt2(1000d * fp / hs.length));
        System.out.println("SHIPPED 逐特征（真人触发率/机器触发率/AUC(机器>真人)）：");
        for (int k = 0; k < ids.length; k++) {
            double[] hv = valuesOf(allH, k), mv = valuesOf(allM, k);
            System.out.println("SHIPPEDFEAT " + pad(ids[k].name(), 19) + " group=" + AigcFeatureId.groupLabel(ids[k])
                    + " weight=" + fmt2(w[k]) + " fireH=" + fmt3(fireRate(hv)) + " fireM=" + fmt3(fireRate(mv))
                    + " AUC=" + fmt3(auc(mv, hv)) + " doc=" + AigcFeatureId.documentLevel(ids[k]));
        }
    }

    // ---- 原始量诊断：在定"把原始量压到 0..1"的刻度之前，先看每档的原始分布长什么样 ----

    private static void raw() throws Exception {
        AigcFeatureId[] ids = AigcFeatureId.values();
        System.out.println("\n# 句级原始量（每档：均值 / 触发率 / 最大）。这些是特征归一化之前的原始计数。");
        String[][] termSets = {
            {"jargon", "AI_JARGON_TERMS", "中文 AI 高频词"},
            {"hedge", "FILLER_HEDGE_TERMS", "填充短语/过度限定"},
            {"attrib", "VAGUE_ATTRIBUTION_TERMS", "模糊归因"},
            {"ordinal", "ORDER_MARKERS", "编号标记（去重）"},
        };
        String[][] segCounts = {{"colons", "colons"}, {"emdashes", "emdashes"}, {"stars", "stars"},
            {"commas", "commas"}, {"semicolons", "semicolons"}};
        for (int t = 0; t < TIERS.length; t++) {
            Tier tier = TIERS[t];
            ArrayList<double[]> acc = new ArrayList<double[]>();
            double[] sums = new double[termSets.length + segCounts.length + 1];
            int sentences = 0, maxes = 0;
            for (String line : paragraphs(tier)) {
                String norm = TextCorpus.normalize(line);
                ArrayList<int[]> spans = TextCorpus.sentences(line);
                for (int i = 0; i < spans.size(); i++) {
                    AigcFeatures.Segment seg = AigcFeatures.segment(norm, spans.get(i)[0], spans.get(i)[1]);
                    if (!AigcFeatures.scores(seg)) continue;
                    sentences++;
                    if (seg.paraFinal) maxes++;
                    int at = 0;
                    for (int k = 0; k < termSets.length; k++) {
                        double v = "ordinal".equals(termSets[k][0])
                                ? distinctMarkers(seg.body) : (double) AigcFeatures.countOccurrences(seg.body, termsOf(termSets[k][1]));
                        sums[at] += v;
                        at++;
                    }
                    for (int k = 0; k < segCounts.length; k++) {
                        sums[at] += count(seg, segCounts[k][1]);
                        if (count(seg, segCounts[k][1]) > maxAt[k]) maxAt[k] = count(seg, segCounts[k][1]);
                        at++;
                    }
                    sums[at] += seg.paraFinal ? 1 : 0;
                }
            }
            StringBuilder out = new StringBuilder("RAW " + pad(tier.code, 10) + " sentences=" + sentences + "  ");
            int at = 0;
            for (int k = 0; k < termSets.length; k++, at++) {
                out.append(termSets[k][0]).append("=").append(fmt3(sums[at] / Math.max(1, sentences))).append(' ');
            }
            for (int k = 0; k < segCounts.length; k++, at++) {
                out.append(segCounts[k][0]).append("=").append(fmt3(sums[at] / Math.max(1, sentences)))
                        .append("(max ").append(maxAt[k]).append(") ");
            }
            out.append("paraFinal=").append(fmt3(sums[at] / Math.max(1, sentences)));
            System.out.println(out.toString());
        }
        maxAt = new int[8];
        System.out.println("\n# 段级（文档级特征的分母与原始比例）：每档取段落均值");
        for (int t = 0; t < TIERS.length; t++) {
            Tier tier = TIERS[t];
            int docs = 0;
            double[] s = new double[8];
            for (String line : paragraphs(tier)) {
                String norm = TextCorpus.normalize(line);
                ArrayList<int[]> spans = TextCorpus.sentences(line);
                ArrayList<AigcFeatures.Segment> scored = new ArrayList<AigcFeatures.Segment>();
                for (int i = 0; i < spans.size(); i++) {
                    AigcFeatures.Segment seg = AigcFeatures.segment(norm, spans.get(i)[0], spans.get(i)[1]);
                    if (AigcFeatures.scores(seg)) scored.add(seg);
                }
                AigcFeatures.DocStats st = AigcFeatures.stats(norm, spans, scored);
                docs++;
                s[0] += st.sentences; s[1] += st.scoredSentences; s[2] += st.lengthCv;
                s[3] += st.adjacentFlatRatio; s[4] += st.shapeRepeatRatio; s[5] += st.hapaxRatio;
                s[6] += st.copulaRatio; s[7] += st.dominantRatio;
            }
            System.out.println("RAWDOC " + pad(tier.code, 10) + " docs=" + docs + " sentences=" + fmt3(s[0] / docs)
                    + " scored=" + fmt3(s[1] / docs) + " cv=" + fmt3(s[2] / docs)
                    + " adjFlat=" + fmt3(s[3] / docs) + " shapeRepeat=" + fmt3(s[4] / docs)
                    + " hapax=" + fmt3(s[5] / docs) + " copula=" + fmt3(s[6] / docs)
                    + " openingEcho=" + fmt3(s[7] / docs));
        }
    }

    private static int[] maxAt = new int[8];

    private static int count(AigcFeatures.Segment seg, String which) {
        if ("colons".equals(which)) return seg.colons;
        if ("emdashes".equals(which)) return seg.emdashes;
        if ("stars".equals(which)) return seg.stars;
        if ("commas".equals(which)) return seg.commas;
        return seg.semicolons;
    }

    private static double distinctMarkers(String body) {
        int n = 0;
        for (int i = 0; i < AigcFeatures.ORDER_MARKERS.length; i++) {
            if (body.indexOf(AigcFeatures.ORDER_MARKERS[i]) >= 0) n++;
        }
        return n;
    }

    private static String[] termsOf(String name) {
        if ("AI_JARGON_TERMS".equals(name)) return AigcFeatures.AI_JARGON_TERMS;
        if ("FILLER_HEDGE_TERMS".equals(name)) return AigcFeatures.FILLER_HEDGE_TERMS;
        if ("VAGUE_ATTRIBUTION_TERMS".equals(name)) return AigcFeatures.VAGUE_ATTRIBUTION_TERMS;
        return AigcFeatures.ORDER_MARKERS;
    }

    private static ArrayList<String> paragraphs(Tier tier) throws Exception {
        ArrayList<String> out = new ArrayList<String>();
        BufferedReader read = new BufferedReader(
                new InputStreamReader(new FileInputStream(tier.path), Charset.forName("UTF-8")));
        String line;
        while ((line = read.readLine()) != null) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) continue;
            if (TextCorpus.normalize(text).length() < tier.minChars) continue;
            out.add(text);
        }
        read.close();
        return out;
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "screen";
        load();
        if ("auditpool".equals(mode)) auditPool();
        else if ("ceiling".equals(mode)) ceiling();
        else if ("matched".equals(mode)) matched();
        else if ("shipped".equals(mode)) shipped();
        else if ("raw".equals(mode)) raw();
        else if ("screen".equals(mode)) screen();
        else if ("fit".equals(mode)) fit();
        else throw new IllegalArgumentException("mode = screen | fit");
        System.out.println("\nnote: 这台拟合台不落表。候选系数由人写进 AigcScorer 之后，"
                + "以 tests/AigcFeatureAuditRegression 与 pwsh tools/test-host.ps1 为准。");
    }
}
package com.rikkahub.wordlite;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;

/**
 * 离线字符 n-gram 分类器：字符 2/3/4-gram + 词表 + 逻辑回归，外加一张字级 1/2/3-gram 频率表做滑窗困惑度代理。
 * 纯手写打分，不引 ONNX、不引新依赖；权重与频率表都是文本文件（见 assets/aigc/）。
 *
 * <p><b>这一版是"装得上、但不许用"的状态。</b>训练台（{@code tools/build-aigc-model.py}）在真稿独立留出
 * 上量到 AUC(机器&gt;真人)=0.558、真人误报 3.2/千句，出厂门槛是 0.85 与 2/千句，两条都不过，所以清单里
 * {@code calibrated=false}：{@link #engaged()} 恒为 false，{@link AigcDetector} 走原启发式路径，
 * 报告与界面一个 AIGC 百分比都不印。全部实测与失效分析见 docs/aigc-offline-model.md。
 *
 * <p>打分口径（必须与 Python 训练台逐字一致，黄金样本在回归里钉着）：
 * <pre>输入   = TextCorpus.compactOf(原句)              与 AigcFeatures.Segment#compact 同一个串
 * x(gram) = 1 + ln(该 gram 在句内出现次数)              次线性词频，只算权重表里有的 gram
 * 句分   = sigmoid(截距 + Σ x·w / ‖x‖₂ + 偏置)          ‖x‖₂ 也只在这份导出表上算，两侧才同一个数
 * 滑窗   = 句内长 24 码点、步长 8 的窗口，按 三字-&gt;二字-&gt;单字 查 log2 概率，
 *          窗口 bits/字 &lt; 阈值 即"平滑窗口"（3.0.2 那条：低于阈值才计入可疑字数）</pre>
 *
 * <p>为什么分数要在导出表上归一化而不是在全量词表上：手机侧只带得起导出表，全量词表的范数它算不出来。
 * 所以训练台是"先按 |系数| 剪到 20,000 条，再在子集上重拟合"，导出文件里的每一条都是真正参与打分的项。
 */
public final class AigcNgramModel {
    /** 随包文件（assets 下的相对路径）。 */
    public static final String FILE_WEIGHTS = "aigc/aigc-zh-weights.tsv";
    public static final String FILE_FREQ = "aigc/aigc-zh-freq.tsv";
    public static final String FILE_MANIFEST = "aigc/aigc-model.tsv";

    /** 出厂门槛（定死，与训练台 tools/build-aigc-model.py 里的常量同值；不许在清单里放松）。 */
    public static final double GATE_HOLDOUT_AUC = 0.85d;
    public static final double GATE_HUMAN_FP_PER_MILLE = 2.0d;
    public static final double GATE_TEMPLATE_WINDOW_HIT = 0.90d;
    public static final double GATE_HUMAN_WINDOW_HIT = 0.0d;

    /** 开销上限：手机侧一次装表的上限，超了直接拒装（回归里也钉这几个数）。 */
    public static final int MAX_WEIGHT_ROWS = 60000;
    public static final int MAX_FREQ_ROWS = 120000;
    public static final int MAX_MANIFEST_ROWS = 512;
    public static final int MAX_WINDOWS_PER_SENTENCE = 64;

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private static volatile AigcNgramModel active;

    /** 清单里读出来的东西（都是可核对的字面量，报告与界面文案都从这里取）。 */
    public static final class Manifest {
        public String version = "";
        public String what = "";
        public String provenance = "";
        public int ngramMin = 2, ngramMax = 4;
        public double intercept, bias, oovLog2 = -24d, windowThreshold = Double.NaN;
        public int windowChars = 24, windowStride = 8, windowMinChars = 12;
        public double publicValidAuc = Double.NaN, holdoutAuc = Double.NaN, holdoutAucParagraph = Double.NaN;
        public double holdoutHumanFpPerMille = Double.NaN, holdoutMachineHitPercent = Double.NaN;
        public double templateWindowHit = Double.NaN, humanWindowHit = Double.NaN;
        public boolean calibrated, windowCalibrated;
        public final ArrayList<String[]> golden = new ArrayList<String[]>();      // {golden, 原句, 侧别, 分数}\n        public final ArrayList<String[]> goldenBits = new ArrayList<String[]>();  // {golden_bits, 原句, bits/字}
    }

    private final Manifest manifest = new Manifest();
    private long[] weightKeys = new long[0];
    private float[] weightValues = new float[0];
    private long[] freqKeys = new long[0];
    private float[] freqValues = new float[0];
    private int weightRows, freqRows, tableSlots;

    private AigcNgramModel() { }

    // ---------------------------------------------------------------- 装载

    /** 当前生效的模型；没装就是 null。 */
    public static AigcNgramModel current() {
        return active;
    }

    /** 装一次（app 侧从 assets 开流，主机回归从文件开流，两边同一个入口）。 */
    public static AigcNgramModel install(InputStream weights, InputStream freq, InputStream manifestStream)
            throws IOException {
        AigcNgramModel model = new AigcNgramModel();
        model.readManifest(manifestStream);
        model.readTable(weights, true);
        model.readTable(freq, false);
        active = model;
        return model;
    }

    private static boolean loadAttempted;

    /**
     * 从随包资源装载一次。读不到只当"没有模型"：查重不许因为字库缺失中断，界面也不许因此
     * 多印一个数——engaged() 只看实测门槛，不看文件在不在。
     */
    public static void loadFromAssets(android.content.Context context) {
        if (loadAttempted || active != null) return;
        loadAttempted = true;
        try {
            install(context.getAssets().open(FILE_WEIGHTS), context.getAssets().open(FILE_FREQ),
                    context.getAssets().open(FILE_MANIFEST));
        } catch (Exception missing) {
            active = null;
            android.util.Log.w("WordliteAigc", "offline model assets unreadable: " + missing);
        }
    }

    /** 主机回归用：让 loadFromAssets 的下一次调用真的重读一遍。 */
    public static void resetLoadAttempt() {
        loadAttempted = false;
    }

    public static void uninstall() {
        active = null;
    }

    private void readManifest(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF8));
        String line;
        int rows = 0;
        while ((line = r.readLine()) != null) {
            if (line.length() == 0 || line.charAt(0) == '#') continue;
            if (++rows > MAX_MANIFEST_ROWS)
                throw new IOException("清单行数 " + rows + " 超过上限 " + MAX_MANIFEST_ROWS);
            String[] p = line.split("\t", -1);
            if (p.length < 2 || "golden".equals(p[0])) {
                if (p.length >= 4 && "golden".equals(p[0])) manifest.golden.add(p);
                continue;
            }
            String k = p[0], v = p[1];
            if ("model_version".equals(k)) manifest.version = v;
            else if ("what".equals(k)) manifest.what = v;
            else if ("provenance".equals(k)) manifest.provenance = v;
            else if ("ngram_min".equals(k)) manifest.ngramMin = Integer.parseInt(v);
            else if ("ngram_max".equals(k)) manifest.ngramMax = Integer.parseInt(v);
            else if ("intercept".equals(k)) manifest.intercept = Double.parseDouble(v);
            else if ("bias".equals(k)) manifest.bias = Double.parseDouble(v);
            else if ("oov_log2".equals(k)) manifest.oovLog2 = Double.parseDouble(v);
            else if ("window_chars".equals(k)) manifest.windowChars = Integer.parseInt(v);
            else if ("window_stride".equals(k)) manifest.windowStride = Integer.parseInt(v);
            else if ("window_min_chars".equals(k)) manifest.windowMinChars = Integer.parseInt(v);
            else if ("window_threshold".equals(k)) manifest.windowThreshold = Double.parseDouble(v);
            else if ("public_valid_auc".equals(k)) manifest.publicValidAuc = Double.parseDouble(v);
            else if ("holdout_auc".equals(k)) manifest.holdoutAuc = Double.parseDouble(v);
            else if ("holdout_auc_paragraph".equals(k)) manifest.holdoutAucParagraph = Double.parseDouble(v);
            else if ("holdout_human_fp_per_mille".equals(k)) manifest.holdoutHumanFpPerMille = Double.parseDouble(v);
            else if ("holdout_machine_hit_percent".equals(k)) manifest.holdoutMachineHitPercent = Double.parseDouble(v);
            else if ("template_window_hit".equals(k)) manifest.templateWindowHit = Double.parseDouble(v);
            else if ("human_window_hit".equals(k)) manifest.humanWindowHit = Double.parseDouble(v);
            else if ("calibrated".equals(k)) manifest.calibrated = "true".equals(v);
            else if ("window_calibrated".equals(k)) manifest.windowCalibrated = "true".equals(v);
        }
        if (manifest.version.length() == 0) throw new IOException("清单缺 model_version");
    }

    private void readTable(InputStream in, boolean weights) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF8));
        ArrayList<long[]> keys = new ArrayList<long[]>();
        ArrayList<String> grams = new ArrayList<String>();
        ArrayList<Float> values = new ArrayList<Float>();
        String line;
        int rows = 0, cap = weights ? MAX_WEIGHT_ROWS : MAX_FREQ_ROWS;
        while ((line = r.readLine()) != null) {
            if (line.length() == 0 || line.charAt(0) == '#') continue;
            if (++rows > cap) throw new IOException((weights ? "权重" : "频率") + "表行数超过上限 " + cap);
            int a = line.indexOf('\t'), b = a < 0 ? -1 : line.indexOf('\t', a + 1);
            if (a < 0 || b < 0) continue;
            String gram = line.substring(a + 1, b);
            if (gram.length() == 0) continue;
            float value = Float.parseFloat(line.substring(b + 1).trim());
            grams.add(gram);
            values.add(Float.valueOf(value));
        }
        int n = grams.size();
        int slots = 1;
        while (slots < n * 2) slots <<= 1;
        long[] ks = new long[slots];
        float[] vs = new float[slots];
        int placed = 0;
        for (int i = 0; i < n; i++) {
            long h = hash(grams.get(i));
            if (h == 0L) h = 1L;                            // 0 是空槽标记
            int at = (int) (h & (slots - 1));
            while (ks[at] != 0L) {
                if (ks[at] == h) { placed++; at = (at + 1) & (slots - 1); continue; }  // 同 gram 重复行：后写覆盖
                at = (at + 1) & (slots - 1);
            }
            ks[at] = h;
            vs[at] = ((Float) values.get(i)).floatValue();
            placed++;
        }
        if (weights) { weightKeys = ks; weightValues = vs; weightRows = placed; }
        else { freqKeys = ks; freqValues = vs; freqRows = placed; }
        tableSlots += slots;
    }

    private static long hash(String s) {
        long h = FNV_OFFSET;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= FNV_PRIME;
        }
        return h;
    }

    private static float get(long[] keys, float[] values, long key) {
        if (keys.length == 0 || key == 0L) return 0f;
        int mask = keys.length - 1;
        int at = (int) (key & mask);
        int guard = 0;
        while (keys[at] != 0L) {
            if (keys[at] == key) return values[at];
            if (++guard > keys.length) return 0f;
            at = (at + 1) & mask;
        }
        return 0f;
    }
    // ---------------------------------------------------------------- 打分

    private static final class Scratch {
        long[] keys = new long[4096];
        int[] counts = new int[4096];
        int[] used = new int[4096];
        int usedCount;
    }

    private static final ThreadLocal<Scratch> SCRATCH = new ThreadLocal<Scratch>() {
        protected Scratch initialValue() {
            return new Scratch();
        }
    };

    private static int bump(long[] keys, int[] counts, int[] used, Scratch s, long key) {
        if (key == 0L) key = 1L;
        int mask = keys.length - 1;
        int at = (int) (key & mask);
        while (keys[at] != 0L && keys[at] != key) at = (at + 1) & mask;
        if (keys[at] == 0L) {
            keys[at] = key;
            counts[at] = 0;
            if (s.usedCount >= used.length) {
                int[] bigger = new int[used.length * 2];
                System.arraycopy(used, 0, bigger, 0, used.length);
                s.used = bigger;
            }
            s.used[s.usedCount++] = at;
        }
        return ++counts[at];
    }

    private static void clear(Scratch s) {
        for (int i = 0; i < s.usedCount; i++) {
            s.keys[s.used[i]] = 0L;
            s.counts[s.used[i]] = 0;
        }
        s.usedCount = 0;
    }

    /** 句分 = P(机器)。表没装、句子太短或一个特征都没命中时给 0，不给一个看起来像结论的中间数。 */
    public double score(String compact) {
        if (compact == null || compact.length() == 0 || weightKeys.length == 0) return 0d;
        char[] arr = compact.toCharArray();
        int[] start = codePointStarts(arr);
        int n = start.length - 1;
        if (n < manifest.ngramMin) return 0d;
        Scratch s = SCRATCH.get();
        try {
            for (int i = 0; i < n; i++) {
                for (int len = manifest.ngramMin; len <= manifest.ngramMax; len++) {
                    if (i + len > n) break;
                    bump(s.keys, s.counts, s.used, s, gramHash(arr, start, i, len));
                }
            }
            double dot = 0d, sumSq = 0d;
            for (int i = 0; i < s.usedCount; i++) {
                int at = s.used[i];
                double x = 1d + Math.log((double) s.counts[at]);
                float w = get(weightKeys, weightValues, s.keys[at]);
                if (w == 0f) continue;
                dot += x * w;
                sumSq += x * x;
            }
            double z = manifest.intercept + manifest.bias + (sumSq > 0d ? dot / Math.sqrt(sumSq) : 0d);
            return 1d / (1d + Math.exp(-Math.max(-40d, Math.min(40d, z))));
        } finally {
            clear(s);
        }
    }

    /** 困惑度代理：窗口内每字 log2 概率的平均，取负就是 bits/字。查不到三级就退二级再退单字。 */
    public double bitsPerChar(String compact, int cpFrom, int cpTo) {
        if (compact == null || compact.length() == 0) return 0d;
        char[] arr = compact.toCharArray();
        int[] start = codePointStarts(arr);
        int n = start.length - 1;
        int from = Math.max(0, cpFrom), to = Math.min(n, cpTo);
        if (to <= from || freqKeys.length == 0) return 0d;
        double total = 0d;
        int counted = 0;
        for (int i = from; i < to; i++) {
            double logp = Double.NaN;
            for (int len = 3; len >= 1; len--) {
                int begin = Math.max(0, i - len + 1);
                if (i - begin + 1 != len) continue;               // 句首不够长就不查这一级（与训练台同规矩）
                float v = get(freqKeys, freqValues, gramHash(arr, start, begin, len));
                if (v != 0f) { logp = v; break; }
            }
            if (Double.isNaN(logp)) logp = manifest.oovLog2;
            total += logp;
            counted++;
        }
        return counted == 0 ? 0d : -total / (double) counted / Math.log(2d);
    }

    /** 句内滑窗的最小 bits/字；一个窗口都没有（句子比窗口短）就整句当窗口。 */
    public double bestWindowBits(String compact) {
        if (compact == null || compact.length() == 0) return Double.NaN;
        int n = compact.codePointCount(0, compact.length());
        int w = Math.max(1, manifest.windowChars), stride = Math.max(1, manifest.windowStride);
        double best = Double.NaN;
        int seen = 0;
        int i = 0;
        while (i < n && seen < MAX_WINDOWS_PER_SENTENCE) {
            int end = Math.min(n, i + w);
            if (end - i >= Math.min(manifest.windowMinChars, n)) {
                double bits = bitsPerChar(compact, i, end);
                if (Double.isNaN(bits) || bits < best) best = bits;
                seen++;
            }
            if (end == n) break;
            i += stride;
        }
        return Double.isNaN(best) && n > 0 ? bitsPerChar(compact, 0, n) : best;
    }

    /** 滑窗判据：只有达标出厂（windowCalibrated）才允许把窗口当可疑字数用。 */
    public boolean windowHit(String compact) {
        if (!windowCalibrated()) return false;
        double bits = bestWindowBits(compact);
        return !Double.isNaN(bits) && bits < manifest.windowThreshold;
    }

    /** 接进检测链路的那一个数：模型句分与滑窗硬判取大（窗口过线就是把这句整句按可疑计，字数不重复计）。 */
    public double sentenceScore(String compact) {
        double p = score(compact);
        return windowHit(compact) ? 1d : p;
    }

    private static int[] codePointStarts(char[] arr) {
        int count = new String(arr).codePointCount(0, arr.length);
        int[] out = new int[count + 1];
        int at = 0;
        for (int i = 0; i < arr.length; ) {
            out[at++] = i;
            i += Character.isHighSurrogate(arr[i]) && i + 1 < arr.length && Character.isLowSurrogate(arr[i + 1]) ? 2 : 1;
        }
        out[at] = arr.length;
        return out;
    }

    private static long gramHash(char[] arr, int[] start, int from, int len) {
        long h = FNV_OFFSET;
        int a = start[from], b = start[Math.min(start.length - 1, from + len)];
        for (int i = a; i < b; i++) {
            h ^= (long) arr[i];
            h *= FNV_PRIME;
        }
        return h == 0L ? 1L : h;
    }

    // ---------------------------------------------------------------- 出厂闸门与自述

    /** 句级打分够不够格对外给数：清单说达标 **且** 那两个实测数真的过线，才算达标。 */
    public static boolean calibrated() {
        AigcNgramModel m = active;
        return m != null && m.manifest.calibrated
                && m.manifest.holdoutAuc >= GATE_HOLDOUT_AUC
                && m.manifest.holdoutHumanFpPerMille <= GATE_HUMAN_FP_PER_MILLE;
    }

    /** 滑窗通道够不够格把窗口算进可疑字数。 */
    public static boolean windowCalibrated() {
        AigcNgramModel m = active;
        return m != null && m.manifest.windowCalibrated
                && m.manifest.templateWindowHit >= GATE_TEMPLATE_WINDOW_HIT
                && m.manifest.humanWindowHit <= GATE_HUMAN_WINDOW_HIT;
    }

    /** 检测链路唯一该问的那一位：装了且达标才用模型打分。 */
    public static boolean engaged() {
        return calibrated();
    }

    public Manifest manifest() {
        return manifest;
    }

    public int weightRows() {
        return weightRows;
    }

    public int freqRows() {
        return freqRows;
    }

    public int tableSlots() {
        return tableSlots;
    }

    /** 这一分数是哪来的：什么模型、在哪些数据上量的、量到多少。报告与界面要说的那一句从这里出。 */
    public static String describe() {
        AigcNgramModel m = active;
        if (m == null) return "离线模型未装载";
        return "离线模型 " + m.manifest.version + "（" + m.manifest.what + "）；" + m.manifest.provenance;
    }
}

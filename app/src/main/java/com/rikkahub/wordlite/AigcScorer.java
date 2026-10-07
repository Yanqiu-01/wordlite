package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * AIGC 打分：组内取最强证据、组间线性相加。系数只在这一个类里，标定台能在运行时换掉它。
 *
 * 为什么不是纯线性（{@code 句分 = Σ 特征值 × 系数}）：真实论文正文实测（tests/corpus/real-prose.txt，
 * 78 段 288 句，表格见 docs/aigc-calibration.md）里，节奏与词汇那几条每千句触发 150~243 次，是中文学术
 * 写作的常态而不是机器腔。0.5.4 把它们与模板句式线性相加，最像机写的真人句子已经打到 0.540，越过了
 * 自编率那条 0.5 的线（余量 −0.040）。把触发最凶的四条系数压下来只把最高分从 0.540 压到 0.480，门槛在
 * 0.45 就还是负余量——问题不在系数高低，在于四条弱证据被重复累加。所以打分单位从"特征"抬到"证据组"：
 *
 * <pre>句分 = min(1, Σ_组 max{ 组内各特征的取值 × 该特征系数 } × 族折扣)</pre>
 *
 * 同组是同一件事的多种说法（分号、破折号、引号、逗号排比都在说"这句在用并列节奏"），叠加等于把一种写作
 * 习惯数四次；跨组才是独立证据。这是对 ROADMAP"特征改为线性模型"的有意收窄，落表与实测一起写进
 * docs/aigc-calibration.md。
 *
 * 系数的身份（务必读完再改数）：这台机器离线、无模型、没有任何带人工标注的人写/机写语料，所以不存在
 * "拟合出一版可信权重"这回事。v1 的每一项都是按**真人侧触发率定序**、再按**真人误报预算封顶**的保守常数，
 * 不是标定值。按 docs/aigc-calibration.md 的定值判据（不与机写骨架表重叠、机写侧触发率高于真人侧、
 * 且真人侧 ≤ 5/千句 三条同时成立才算够格定值），本轮**没有任何一条系数够格定值**，所以版本号叫
 * {@link #VERSION} = v1-order-only。谁要把它写成"已标定"，请先补一版带标注的语料。
 */
public final class AigcScorer {
    /** 产品默认表的版本号。它会写进报告，用来追溯一份倾向是哪一版系数算出来的。 */
    public static final String VERSION = "v1-order-only";
    /** 拉丁族没有真人英文正文可标定，只能按特征名从中文族平移，版本号上明写出来。 */
    static final String LATIN_SUFFIX = "+latin-derived";

    /** 一张系数表：语言族 × 特征系数 + 折扣 + 版本号。 */
    public static final class Coefficients {
        private final double[] weight = new double[AigcFeatureId.COUNT];
        private final int family;
        private final double discount;
        private final String version;

        Coefficients(int family, double discount, String version) {
            this.family = family;
            this.discount = discount;
            this.version = version;
        }

        public double get(AigcFeatureId id) {
            return weight[id.ordinal()];
        }

        /** 改一个系数。只对副本用——{@link #defaults(int)} 每次都给新表，改不到产品默认值。 */
        public Coefficients set(AigcFeatureId id, double value) {
            if (value < 0d) throw new IllegalArgumentException("系数不能为负：" + id + "=" + value);
            weight[id.ordinal()] = value;
            return this;
        }

        public String version() {
            return version;
        }

        public int family() {
            return family;
        }

        double discount() {
            return discount;
        }

        /** 逐特征快照，只放非零项；报告与标定台都读它。 */
        public LinkedHashMap<String, Double> snapshot() {
            LinkedHashMap<String, Double> out = new LinkedHashMap<String, Double>();
            AigcFeatureId[] ids = AigcFeatureId.values();
            for (int i = 0; i < ids.length; i++) {
                if (weight[ids[i].ordinal()] <= 0d) continue;
                out.put(ids[i].name(), Double.valueOf(weight[ids[i].ordinal()]));
            }
            return out;
        }

        /** 组权重（组内最强那一档），键是组的中文名。报告里那一行用它。 */
        public LinkedHashMap<String, Double> groupTotals() {
            double[] best = new double[AigcFeatureId.GROUP_COUNT];
            AigcFeatureId[] ids = AigcFeatureId.values();
            for (int i = 0; i < ids.length; i++) {
                int group = AigcFeatureId.group(ids[i]);
                if (weight[ids[i].ordinal()] > best[group]) best[group] = weight[ids[i].ordinal()];
            }
            LinkedHashMap<String, Double> out = new LinkedHashMap<String, Double>();
            for (int i = 0; i < AigcFeatureId.GROUP_COUNT; i++) {
                if (best[i] <= 0d) continue;
                out.put(AigcFeatureId.GROUP_LABELS[i], Double.valueOf(best[i]));
            }
            return out;
        }

        /** 一行人类可读的系数说明，报告在倾向句表格后面补这一行用。 */
        public String describe() {
            StringBuilder out = new StringBuilder();
            for (java.util.Map.Entry<String, Double> entry : groupTotals().entrySet()) {
                if (out.length() > 0) out.append("，");
                out.append(entry.getKey()).append(' ').append(AigcFeatures.format(entry.getValue().doubleValue()));
            }
            return out.toString();
        }
    }

    /** 三张生效表（中文 / 拉丁 / 混排）。默认由 table() 造，标定台覆盖时整套换掉，还原时整套换回来。 */
    private static final Coefficients[] ACTIVE = new Coefficients[AigcFamily.MIXED + 1];

    static {
        install(ACTIVE, null);
    }

    private AigcScorer() { }

    /** 产品默认表。v1 的每一项都是"由真人误报预算反推的上限"，取值理由见下面逐条注释与标定文档。 */
    public static Coefficients defaults(int family) {
        return table(family, 1.00d, family == AigcFamily.LATIN ? VERSION + LATIN_SUFFIX : VERSION);
    }

    /**
     * v1 表。四条组系数按真人侧触发率从低到高排（套话 20.8 → 连接词 72.9 → 节奏 150~233 → 词汇 243/千句），
     * 数值上限由"最像机写的真人句子必须留在门槛之下"反推，全部是 ORDER-ONLY：无标注语料不能证伪。
     */
    private static Coefficients table(int family, double discount, String version) {
        Coefficients c = new Coefficients(family, discount, version);
        // ORDER-ONLY：真人侧 20.8/千句，是六条特征里最稀有的，但机写骨架表就是用这些词造的（标定台 OVERLAP 行）。
        c.set(AigcFeatureId.TEMPLATE, 0.35d);
        // ORDER-ONLY：真人侧 72.9/千句（中英混数的旧口径），中英分表后更低；上限由误报预算封顶。
        c.set(AigcFeatureId.CONNECTIVE, 0.20d);
        // ORDER-ONLY：真人侧与机写侧实测都是 0/千句，两侧都触发不了的证据不给它抬价。
        c.set(AigcFeatureId.OPENING, 0.20d);
        // ORDER-ONLY：实测与机写弱标签负相关（−60.3/千句），压在节奏组最低一档，保留只因它属于节奏组的同族证据。
        c.set(AigcFeatureId.BURST, 0.10d);
        // ORDER-ONLY：同上，反向证据（−13.1/千句）；真人论文里 152.8/千句。
        c.set(AigcFeatureId.PUNCT_SEMICOLON, 0.10d);
        // ORDER-ONLY：真人论文里 232.6/千句，是最大的一块无据系数，必须低于节奏组的门面系数。
        c.set(AigcFeatureId.PUNCT_DASH, 0.10d);
        // ORDER-ONLY：真人侧 3.5/千句，稀有但没有任何外部依据说它指向机器腔。
        c.set(AigcFeatureId.PUNCT_QUOTE, 0.15d);
        // ORDER-ONLY：节奏组的门面系数（真人侧 222.2/千句，机写骨架侧 398.7/千句，是唯一两侧都高的节奏证据）。
        c.set(AigcFeatureId.PARALLEL, 0.15d);
        // ORDER-ONLY：真人侧 0/千句，无法做误报校验。
        c.set(AigcFeatureId.INTENSIFIER, 0.10d);
        // ORDER-ONLY：真人侧 0/千句，同上。
        c.set(AigcFeatureId.ENTROPY, 0.10d);
        // ORDER-ONLY：真人侧 243.1/千句，触发最凶的一条；组内共享一个系数正好把它和另外两条一起压住。
        c.set(AigcFeatureId.DIVERSITY, 0.10d);
        check(family, c);
        return c;
    }

    /**
     * 静态纪律（改表就绕不过去）：四组系数加和不超过 1.0（句分的上限来自证据组数，不来自某一条能被推到 1）；
     * 且单条系数必须低于区间门槛 {@link AigcDetector#SEGMENT_FLAG_GATE}——**任何一条单独的证据都不足以把
     * 一句推到可疑**，必须有第二条独立证据。这一条直接决定了真人论文里那句"综上所述"不会单独成案。
     */
    private static void check(int family, Coefficients c) {
        double[] group = new double[AigcFeatureId.GROUP_COUNT];
        double strongest = 0d;
        AigcFeatureId[] ids = AigcFeatureId.values();
        for (int i = 0; i < ids.length; i++) {
            int g = AigcFeatureId.group(ids[i]);
            if (c.get(ids[i]) > group[g]) group[g] = c.get(ids[i]);
            if (c.get(ids[i]) > strongest) strongest = c.get(ids[i]);
        }
        double total = 0d;
        for (int i = 0; i < group.length; i++) total += group[i];
        if (total > 1.0d + 1e-9d)
            throw new IllegalStateException("族 " + family + " 的组系数加和 " + total + " 超过 1.0");
        if (strongest >= AigcDetector.SEGMENT_FLAG_GATE)
            throw new IllegalStateException("族 " + family + " 有单条系数 " + strongest
                    + " 不低于区间门槛，一条孤立证据就能把句子推到可疑");
    }

    private static void install(Coefficients[] slot, Coefficients base) {
        Coefficients chinese = base == null ? defaults(AigcFamily.CHINESE) : base;
        // 拉丁族：0.7.0 按特征名从中文族平移（没有英文正文可标定）；覆盖时同样平移，标定台不用管两个族。
        Coefficients latin = new Coefficients(AigcFamily.LATIN, 1.00d,
                (base == null ? VERSION + LATIN_SUFFIX : chinese.version() + LATIN_SUFFIX));
        for (int i = 0; i < AigcFeatureId.COUNT; i++) latin.weight[i] = chinese.weight[i];
        Coefficients mixed = new Coefficients(AigcFamily.MIXED, AigcFamily.MIXED_DISCOUNT, chinese.version());
        for (int i = 0; i < AigcFeatureId.COUNT; i++) mixed.weight[i] = chinese.weight[i];
        check(AigcFamily.CHINESE, chinese);
        check(AigcFamily.LATIN, latin);
        check(AigcFamily.MIXED, mixed);
        slot[AigcFamily.CHINESE] = chinese;
        slot[AigcFamily.LATIN] = latin;
        slot[AigcFamily.MIXED] = mixed;
    }

    /** 本次真正生效的表（产品路径永远等于 {@link #defaults(int)}，只有标定台会临时换掉它）。 */
    public static Coefficients current(int family) {
        return ACTIVE[family];
    }

    /**
     * 标定台用的临时覆盖。默认就是产品表，产品路径一次也不碰它，跑完必须还原；有了这个口子，系数才能在
     * 真实语料上扫而不是写死之后凭感觉调（先例见 {@link TextCorpus#overrideThresholds}）。
     * 覆盖一次管三个族：v1 的三个族本来就同源，0.7.1 若要分开标定再给这个方法加族参数。
     */
    static void overrideCoefficients(Coefficients next) {
        if (next == null) throw new IllegalArgumentException("overrideCoefficients(null)");
        install(ACTIVE, next);
    }

    static void restoreCoefficients() {
        install(ACTIVE, null);
    }

    /** 打分：组内取最强、组间相加、上限 1.0。不再碰任何文本。 */
    public static double score(ArrayList<AigcFeatures.Hit> hits, Coefficients coefficients) {
        if (hits == null || hits.isEmpty() || coefficients == null) return 0d;
        double[] best = new double[AigcFeatureId.GROUP_COUNT];
        for (int i = 0; i < hits.size(); i++) {
            AigcFeatures.Hit hit = hits.get(i);
            if (hit == null || hit.value <= 0d) continue;
            int group = AigcFeatureId.group(hit.id);
            double contribution = hit.value * coefficients.get(hit.id);
            if (contribution > best[group]) best[group] = contribution;
        }
        double total = 0d;
        for (int i = 0; i < best.length; i++) total += best[i];
        total *= coefficients.discount();
        if (total > 1d) return 1d;
        return total < 0d ? 0d : total;
    }
}
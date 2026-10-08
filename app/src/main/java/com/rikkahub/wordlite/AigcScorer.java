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
 *
 * <p>2026-10-08 又试过一次，而且这次是拿着外部依据把特征池从 11 条扩到 33 条之后试的
 * （新增 22 条的定义与出处见 {@link AigcFeatureId} 与 docs/oss-aigc-detection.md，拟合过程见
 * docs/aigc-calibration.md 的"第五台"与 artifacts/agent-aigc/CALIBRATION.md）。结论没变好：
 * 拟合侧最好的表（BURST/OPENING_ECHO/SHAPE_REPEAT 各 0.40，另加 SENT_LEN_SYMMETRY 0.15、COPULA_AVOID 0.05）在拟合侧能让 29/158 个机器句过线、
 * 真人侧 0/440 过线，可一到留出档就 0/104 过线，留出 AUC 只到 0.657（换号门槛 0.75）。
 * 所以这 22 条新特征**一条都不配权重**（下面 table() 里逐条写明为什么是 0），版本号也继续是
 * {@link #VERSION} = v1-order-only。这不是"忘了改"，是量出来的数不够格：
 * 见 {@link #UNCALIBRATED_NOTE} 里那一串 2026-10-08 重量的数字。
 */
public final class AigcScorer {
    /** 产品默认表的版本号。它会写进报告，用来追溯一份倾向是哪一版系数算出来的。 */
    public static final String VERSION = "v1-order-only";
    /**
     * 拉丁族没有真人英文正文可标定，只能按特征名从中文族平移，版本号上明写出来。
     *
     * 前缀写成 {@code latin-derived<-...} 而不是后缀，是一条**结构性**的纪律：中文族将来换成 cal-* 之后，
     * "cal-xxx+latin-derived" 会以 cal- 开头，{@link #calibrated()} 那种"看版本号前缀"的判断就会把
     * 一个从来没有任何英文标注语料的族一起算成已标定。写在前头，拉丁族永远不可能以 cal- 开头。
     */
    static final String LATIN_PREFIX = "latin-derived<-";
    /** 只有带这个前缀的版本号才够格把句分印到报告与界面上，判据只此一处，见 {@link #calibrated()}。 */
    static final String CALIBRATED_PREFIX = "cal-";

    /**
     * 这套判据够不够格对外给一个数。只认 {@link #VERSION} 的前缀，别处不许另立一套判断。
     *
     * 为什么非要这一位挡着：docs/aigc-corpus.md 4.2 节在带标注语料上量到 AUC(机器>真人)=0.305，**方向是反的**
     * （真人句分比机器句分更高）；4.1 节同时量到真人侧最高句分 0.506 已经越过 SEGMENT_FLAG_GATE(0.450)，
     * 机器侧最高只有 0.217，一枪没打。这个刻度现在排的不是"像不像机器"，把它打成 17.5 或 45.3 就是在冒充
     * 一个可比的量。所以未标定期间报告与界面一律降级成"量不到"，先例是同仓库那两条纪律：未完成查重不印
     * 0.00%（DuplicateEngine.aigcTrend）、有效字符不足不给比例（AigcDetector.MIN_DOCUMENT_CHARS）。
     *
     * 把它翻回来的唯一路子（2026-10-08 写死，别再另立标准）：
     * 1) 留出档 AUC(机器>真人) ≥ 0.750，且留出档不参与拟合（本轮留出 = M-EVADE + M-DOMAIN + X1 + 钉子户，
     *    拟合只用 M-RAW + H1/H2/H3）；
     * 2) 真人侧在选定门槛下的误报 ≤ 2 句/千句；
     * 3) 过线的证据不能只靠"段落更长所以文档级特征更容易亮灯"这一类构造差（{@code pwsh tools/aigc-fit.ps1
     *    matched} 那一台专门量这个：把两侧都限定在 ≥5 个计分句的段落里，SHAPE_REPEAT 的拟合侧 AUC 从
     *    0.874 掉到 0.722，剩下的才是真信号）；
     * 4) 换号同时改 tests/AigcFeatureAuditRegression 里钉住的方向结论（它钉死了 VERSION 的字面值，
     *    只改这里不改那里会直接炸），拉丁族继续留派生身份——见 {@link #LATIN_PREFIX} 那条结构性理由。
     * 5) 光看句级 AUC 不够：同一张表还要 (a) 段级留出 AUC（每段一个样本，免得文档级特征在同段里被重复计数）
     *    也 ≥ 0.750，(b) 选定门槛下留出档的机器侧至少打中一句，(c) 逐档配对的留出 AUC 一档都不许反向。
     *
     * 本轮的实际读数（两条候选表各卡在哪儿，全部可在 artifacts/agent-aigc/fit-*.txt 复核）：
     * - 拟合台选出的保守表（OPENING_ECHO 0.15 + SHAPE_REPEAT 0.15）留出档句级 AUC 正好 0.750000，
     *   看着踩线过了第 1 条，但段级留出 AUC 只有 0.700（第 5a 条不过），而且在 0.450 门槛下它留出档
     *   一句机器句都没打中（0/104，第 5b 条不过）——换成印数就是一行没有可疑句、均分 0.04 的空表；
     *   它的领域配对档还是反的：M-DOMAIN vs H2 = 0.212、M-EVADE vs H2 = 0.285（第 5c 条不过）。
     * - 真正能打中句子的天花板表（BURST/OPENING_ECHO/SHAPE_REPEAT 各 0.40 等五条）留出档句级 AUC 只有
     *   0.657（第 1 条不过），0/104 过线。
     * 第 2 条（真人误报 ≤ 2/千句）两张表都过（天花板表全八档 0/498 = 0.00/千句，拟合表各门槛 0/498），
     * 但第 1、5 条不过就没有"能过线的机器句"可给数。所以版本号仍是 v1-order-only：
     * 踩在 0.750000 这条线上、段级掉到 0.700、领域配对档反向的数，印出去就是假数字。
     *
     * <p>2026-10-09 又试了第三条路：不再堆手写特征，直接训练一个字符 n-gram + 逻辑回归的离线模型
     * （{@link AigcNgramModel}，训练台 {@code tools/build-aigc-model.py}，公开带标注语料 27 万计分句）。
     * 它在公共留出上 AUC 0.8352~0.9698（看配置），但在真稿独立留出上只有 0.558，真人误报 3.2 句/千句，
     * 两条出厂门槛（0.85 与 2/千句）都不过，所以那位自己另有一道闸门（{@link AigcNgramModel#calibrated()}），
     * 也不归本方法的版本号管：{@link #VERSION} 说的仍然是这套手写启发式系数。
     * 全过程与失效分析见 docs/aigc-offline-model.md。
     */
    public static boolean calibrated() {
        return VERSION.startsWith(CALIBRATED_PREFIX);
    }

    /**
     * 未标定期间对外只此一句：先说方向，再给两侧实测数字，读的人要能看出错在哪一侧。
     *
     * 这几个数（2026-10-08 重量）出自 tests/AigcFeatureAuditRegression 那七档带标注语料
     * （真人 H1+H2+H3+X1 = 493 计分句，机器 M-RAW+M-EVADE+M-DOMAIN = 262 计分句，其中 M-DOMAIN 是与真人
     * 同领域同主题配对的机器稿）；那个套件把自己现算的 AUC 字符串和这句话对着断言，数字漂了会直接炸。
     * 复现：{@code pwsh tools/aigc-fit.ps1 auditpool}。
     */
    public static final String UNCALIBRATED_NOTE =
            "判据未标定，方向已实测为反：标注语料（真人池 493 句 / 机器池 262 句，含与真人同领域的机器稿）上"
                    + "真人句分比机器句分更高，AUC(机器>真人)=0.312；真人稿已有句子被误判（最高句分 0.506，"
                    + "门槛 0.450，每千句误报 2.03 句），机器稿一句没打中（最高 0.217）。"
                    + "2026-10-08 按外部依据把判据从 11 条扩到 33 条并重新拟合：33 条里只有 4 条方向经标注语料验证为正，"
                    + "但留出档 AUC 最好只到 0.657（换号门槛 0.750），所以新增 22 条一律 0 系数，只作证据，不参与打分";

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
        return table(family, 1.00d, family == AigcFamily.LATIN ? LATIN_PREFIX + VERSION : VERSION);
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

        // ---- 2026-10-08 追加的 22 条候选：全部 0 系数，一条都不进句分。写在表里而不是不写，是为了让
        //      "这条证据存在过、方向量过了、不够格"在系数表这一处就看得见。取过值的证据仍然会进报告的
        //      依据清单（AigcFeatures.of 不看权重），但不进句分、不进档位、不出数。
        //      括号里是标注语料七档池（真人 493 / 机器 262 计分句）上的 AUC(机器>真人) 与两侧触发率，
        //      复现：pwsh tools/aigc-fit.ps1 auditpool。老的 11 条一条没动（BURST 仍是 v1 的 0.10）。
        //
        // 方向为正、两侧都有触发（33 条里只有 4 条够这个资格，老特征里只有 BURST 一条）——但整张表在
        // 留出档 AUC 只到 0.657，低于换号门槛 0.750，所以照样置 0：
        c.set(AigcFeatureId.SHAPE_REPEAT, 0d);        // AUC 0.763 真人触发率 0.300 / 机器 0.779，最强的新证据
        c.set(AigcFeatureId.SENT_LEN_SYMMETRY, 0d);   // AUC 0.588 真人 0.085 / 机器 0.271
        c.set(AigcFeatureId.OPENING_ECHO, 0d);        // AUC 0.577 真人 0.095 / 机器 0.244
        // 方向为反（AUC <= 0.45，真人稿触发得更多）：负系数被 Coefficients.set 拒绝，方向只能在特征定义里
        // 解决，权重这一侧唯一能做的就是 0。
        c.set(AigcFeatureId.CONNECTIVE_LEAD, 0d);     // AUC 0.462 真人 0.075 / 机器 0.000：真人更爱用连接词起笔
        c.set(AigcFeatureId.REPEAT_SPAN, 0d);         // AUC 0.444 真人 0.112 / 机器 0.000：真人技术名词回指更多
        // 分不开（0.45 < AUC < 0.55）：给不了方向，也就给不了权重。
        c.set(AigcFeatureId.TRIPLE_PARALLEL, 0d);     // AUC 0.512 真人 0.174 / 机器 0.202
        c.set(AigcFeatureId.EMDASH_OVERUSE, 0d);      // AUC 0.501 真人 0.002 / 机器 0.004，两侧都几乎没有
        c.set(AigcFeatureId.COLON_LABEL, 0d);         // AUC 0.495 真人 0.024 / 机器 0.015
        c.set(AigcFeatureId.OPEN_THEORY, 0d);         // AUC 0.499 真人 0.002 / 机器 0.000
        c.set(AigcFeatureId.CLOSE_REVEAL, 0d);        // AUC 0.499 真人 0.002 / 机器 0.000
        c.set(AigcFeatureId.PASSIVE_TACKET, 0d);      // AUC 0.498 真人 0.004 / 机器 0.000
        c.set(AigcFeatureId.QUESTION_STUB, 0d);       // AUC 0.496 真人 0.008 / 机器 0.000
        c.set(AigcFeatureId.PARA_SUMMARY, 0d);        // AUC 0.494 真人 0.016 / 机器 0.004
        c.set(AigcFeatureId.COPULA_AVOID, 0d);        // AUC 0.488 真人 0.219 / 机器 0.198
        c.set(AigcFeatureId.EMDASH_ABSENT, 0d);       // AUC 0.486 真人 0.059 / 机器 0.031
        // 两侧都不触发、这批语料根本没有语料可审的七条：谁要配权重，先补能触发它们的语料
        // （编号链/列表标记/加粗要列表体或 markdown 稿，AI 高频词与模糊归提要真换成商用模型的稿子）。
        c.set(AigcFeatureId.NUMBER_CHAIN, 0d);        // 触发率 0.000 / 0.000
        c.set(AigcFeatureId.LIST_MARK, 0d);           // 触发率 0.000 / 0.000（语料是段落体，没有列表）
        c.set(AigcFeatureId.AI_JARGON, 0d);           // 触发率 0.000 / 0.000：机器稿一次都没用"赋能/抓手/闭环"
        c.set(AigcFeatureId.FILLER_HEDGE, 0d);        // 触发率 0.000 / 0.000
        c.set(AigcFeatureId.VAGUE_ATTRIBUTION, 0d);   // 触发率 0.000 / 0.000
        c.set(AigcFeatureId.MARKUP_EMPHASIS, 0d);     // 触发率 0.000 / 0.000：docx 正文里没有 markdown
        c.set(AigcFeatureId.RARE_SHAPE, 0d);          // 触发率 0.000 / 0.000：门槛之上无样本，且方向与假设相反
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
                (base == null ? LATIN_PREFIX + VERSION : LATIN_PREFIX + chinese.version()));
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
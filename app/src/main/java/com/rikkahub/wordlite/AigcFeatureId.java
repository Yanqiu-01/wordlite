package com.rikkahub.wordlite;

/**
 * AIGC 特征的稳定 id 登记表。id 是系数表的下标、标定台的列名、断言的键，一旦发布不再改顺序；
 * 新增特征只能追加在末尾，并且必须同时进 {@link #group} 与 {@link AigcScorer} 的每一张系数表。
 *
 * 证据组是关键的一步：实测（docs/aigc-calibration.md）显示真人论文里"分号、破折号、逗号排比、句长太匀"
 * 这几条每千句触发 150~233 次，它们是同一件事（这句在用并列节奏）的四种说法。按特征线性相加等于把一种
 * 写作习惯数四次，最高分的真人句子因此越过 0.5 的门槛。组内只取最强的一条，跨组才算独立证据。
 *
 * 第 12 条起是 2026-10-08 标定轮追加的候选（docs/aigc-corpus.md 第九节量到原来 11 条里只有 BURST 一条
 * 方向为正，重新加权救不回来）。每条的外部依据是 docs/oss-aigc-detection.md 第二节那份中文模式清单
 * （redbaronyyyyy-eng/humanizer-zh-academic 的 16 条，逐条编号写在下面每条注释里）与
 * Moonlit-Pages/AIGC-Detector-Rewriter-Skill 的风险模式清单。**这些定义一律不是从
 * tests/corpus/aigc-frames.txt 那份骨架表推出来的**：骨架表只有 30 个 TOKEN 与 12 张骨架，用它定义特征
 * 再用它打分是循环论证（标定台 OVERLAP 行点名的就是这个坑）。凡是与骨架表 TOKEN 有交集的表，
 * 都由 {@code AigcCalibrationSweep.overlappedFeatures()} 实际比对后点名，不靠这里口头声明。
 * 许可纪律：Gx664/AIGC 是 PolyForm Noncommercial，只读做法，本仓库没有从它搬过一行代码。
 */
public enum AigcFeatureId {
    /** 模板句式命中（中英分表，见 {@link AigcFeatures}）。 */
    TEMPLATE,
    /** 连接词密度（按族各一张表，不再中英混数）。 */
    CONNECTIVE,
    /** 句首结构重复（文档级证据回灌到句）。 */
    OPENING,
    /** 句长突发度低（文档级证据回灌到句）。 */
    BURST,
    /** 分号并列。 */
    PUNCT_SEMICOLON,
    /** 破折号插入语。 */
    PUNCT_DASH,
    /** 引号密度。 */
    PUNCT_QUOTE,
    /** 逗号排比。 */
    PARALLEL,
    /** 评价词/程度副词堆叠。 */
    INTENSIFIER,
    /** 字符二元组熵偏低。 */
    ENTROPY,
    /** 实词多样度低。 */
    DIVERSITY,

    // ---- 2026-10-08 标定轮追加（下面 20 条一律先以 0 系数入库，方向量正了才配系数）----

    /** 句式 #1：「依据/基于 XX 理论/视角/框架」起笔。 */
    OPEN_THEORY,
    /** 句式 #2：「此案例印证了/揭示了/凸显了」这类段末揭示套路。 */
    CLOSE_REVEAL,
    /** 句式 #3：「首先/其次/再次/第一/其一」编号链在同句内成串。 */
    NUMBER_CHAIN,
    /** 句式 #4：「该处理体现了/该设计基于」被动分析套话。 */
    PASSIVE_TACKET,
    /** 句式 #5：「面临的核心问题是」模板化问题陈述。 */
    QUESTION_STUB,
    /** 句式 #7 + #10：段落末句的冗余总结与泛化结论（位置感知，只有段落末句会亮）。 */
    PARA_SUMMARY,
    /** 重复句首（AIGC-Detector 风险模式一）：OPENING 的软版本，不设 0.45 硬门槛。文档级。 */
    OPENING_ECHO,
    /** 「X：Y；Z：W」冒号标签结构密度（机械段落结构 + 句式 #6 对称标签）。 */
    COLON_LABEL,
    /** 列表/编号标记密度（机械段落结构：项目符号、①②、1)/一、）。 */
    LIST_MARK,
    /** 句式 #6 + #13：高度对称的三元并列句式（三个长度相近的并列小句）。 */
    TRIPLE_PARALLEL,
    /** 句式长度对称性（BURST 的姊妹证据：相邻句长几乎等长的比例）。文档级。 */
    SENT_LEN_SYMMETRY,
    /** 段落内句式重复度：同一段里"逗号数 + 分号数 + 句长档"完全同型的句子占比。文档级。 */
    SHAPE_REPEAT,
    /** 风格 #15 的一个方向：「——」破折号过量。 */
    EMDASH_OVERUSE,
    /** 风格 #15 的另一个方向：整段一个破折号都不用的"完全不用"。文档级。 */
    EMDASH_ABSENT,
    /** 语言 #11：中文 AI 高频词（赋能/抓手/闭环/范式/凸显…）密度。 */
    AI_JARGON,
    /** 语言 #9：填充短语与过度限定（在一定程度上/从某种意义上说/在特定语境下）。 */
    FILLER_HEDGE,
    /** 语言 #8：模糊归因（有学者指出/研究表明/业界普遍认为——没有出处的引用）。 */
    VAGUE_ATTRIBUTION,
    /** 语言 #12：主动回避「是」的系动词替换比例（作为/堪称/构成/视为）。文档级。 */
    COPULA_AVOID,
    /** 风格 #16：加粗/强调标记密度（**…**、__…__）。 */
    MARKUP_EMPHASIS,
    /** 低罕见串比例：文档内只出现一次的字符二元组占比低（GLTR 那条"罕见 token"口径的离线替身）。文档级。 */
    RARE_SHAPE,
    /** 过度顺滑的逻辑链（AIGC-Detector 风险模式二）：整段里"每句都用连接词起笔"的比例。文档级。 */
    CONNECTIVE_LEAD,
    /** 文内最长重复片段（DNA-GPT 那条"子串重复/分支熵"口径的离线替身，也是查重侧 n 元重复的同一条纪律）。文档级。 */
    REPEAT_SPAN;

    /** 系数表与特征表的长度，编译期常量。 */
    static final int COUNT = values().length;

    /** 套话组：万能句本身。 */
    static final int GROUP_CLICHE = 0;
    /** 句式框架组：连接词、句首复读、提纲式标签，说的是"这段话是按提纲填的"。 */
    static final int GROUP_FRAME = 1;
    /** 节奏组：句长太匀 + 分号/破折号/引号/逗号排比 + 对称句式，说的是"这句在用并列节奏"。 */
    static final int GROUP_RHYTHM = 2;
    /** 词汇组：评价词堆叠、低熵串、用词重复、AI 高频词与填充语。 */
    static final int GROUP_LEXIS = 3;
    static final int GROUP_COUNT = 4;
    /** 组的中文名，进报告与标定台表头。 */
    static final String[] GROUP_LABELS = {"套话", "句式框架", "节奏", "词汇"};

    /** 同组内的弱证据不叠加，只取最强的一条（理由与实测见 docs/aigc-calibration.md）。 */
    static int group(AigcFeatureId id) {
        switch (id) {
            case TEMPLATE:
                return GROUP_CLICHE;
            case CONNECTIVE:
            case OPENING:
            case OPEN_THEORY:
            case CLOSE_REVEAL:
            case NUMBER_CHAIN:
            case PASSIVE_TACKET:
            case QUESTION_STUB:
            case PARA_SUMMARY:
            case OPENING_ECHO:
            case COLON_LABEL:
            case LIST_MARK:
                return GROUP_FRAME;
            case BURST:
            case PUNCT_SEMICOLON:
            case PUNCT_DASH:
            case PUNCT_QUOTE:
            case PARALLEL:
            case TRIPLE_PARALLEL:
            case SENT_LEN_SYMMETRY:
            case SHAPE_REPEAT:
            case EMDASH_OVERUSE:
            case EMDASH_ABSENT:
                return GROUP_RHYTHM;
            case INTENSIFIER:
            case ENTROPY:
            case DIVERSITY:
            case AI_JARGON:
            case FILLER_HEDGE:
            case VAGUE_ATTRIBUTION:
            case COPULA_AVOID:
            case MARKUP_EMPHASIS:
            case RARE_SHAPE:
            case CONNECTIVE_LEAD:
            case REPEAT_SPAN:
                return GROUP_LEXIS;
            default:
                throw new IllegalStateException("特征 " + id + " 没有登记证据组");
        }
    }

    /** 这条证据是不是文档级（同一文档里每句取同一个值）。报告与审计用它解释"为什么每句同分"。 */
    static boolean documentLevel(AigcFeatureId id) {
        switch (id) {
            case OPENING:
            case BURST:
            case OPENING_ECHO:
            case SENT_LEN_SYMMETRY:
            case SHAPE_REPEAT:
            case EMDASH_ABSENT:
            case COPULA_AVOID:
            case RARE_SHAPE:
            case CONNECTIVE_LEAD:
            case REPEAT_SPAN:
                return true;
            default:
                return false;
        }
    }

    static String groupLabel(AigcFeatureId id) {
        return GROUP_LABELS[group(id)];
    }
}
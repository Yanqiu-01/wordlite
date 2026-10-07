package com.rikkahub.wordlite;

/**
 * AIGC 特征的稳定 id 登记表。id 是系数表的下标、标定台的列名、断言的键，一旦发布不再改顺序；
 * 新增特征只能追加在末尾，并且必须同时进 {@link #group} 与 {@link AigcScorer} 的每一张系数表。
 *
 * 证据组是关键的一步：实测（docs/aigc-calibration.md）显示真人论文里"分号、破折号、逗号排比、句长太匀"
 * 这几条每千句触发 150~233 次，它们是同一件事（这句在用并列节奏）的四种说法。按特征线性相加等于把一种
 * 写作习惯数四次，最高分的真人句子因此越过 0.5 的门槛。组内只取最强的一条，跨组才算独立证据。
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
    DIVERSITY;

    /** 系数表与特征表的长度，编译期常量。 */
    static final int COUNT = values().length;

    /** 套话组：万能句本身。 */
    static final int GROUP_CLICHE = 0;
    /** 句式框架组：连接词与句首复读，说的是"这段话是按提纲填的"。 */
    static final int GROUP_FRAME = 1;
    /** 节奏组：句长太匀 + 分号/破折号/引号/逗号排比，说的是"这句在用并列节奏"。 */
    static final int GROUP_RHYTHM = 2;
    /** 词汇组：评价词堆叠、低熵串、用词重复。 */
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
                return GROUP_FRAME;
            case BURST:
            case PUNCT_SEMICOLON:
            case PUNCT_DASH:
            case PUNCT_QUOTE:
            case PARALLEL:
                return GROUP_RHYTHM;
            case INTENSIFIER:
            case ENTROPY:
            case DIVERSITY:
                return GROUP_LEXIS;
            default:
                throw new IllegalStateException("特征 " + id + " 没有登记证据组");
        }
    }

    static String groupLabel(AigcFeatureId id) {
        return GROUP_LABELS[group(id)];
    }
}
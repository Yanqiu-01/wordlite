package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * AIGC 特征提取：只读文本，只出数字，一个权重都不碰。同一个输入永远给同一个取值，所以每条特征都能单独断言、
 * 单独在真实语料上扫触发率（{@code tests/AigcCalibrationSweep.java} 逐条走 {@link #value}）。
 *
 * 取值一律 0..1，口径与出处逐条写在下面每个方法上；线性模型要的 0..1 在 0.5.4 只存在于注释里
 * （命中就加一条写死的权重），这一版才算真的给出连续取值。归一化用的门槛（句长变异系数 0.28、二元组熵
 * 3.4 bit、多样度 0.78/0.62）属于特征定义，留在这里；把它们乘以系数再相加的是 {@link AigcScorer}。
 *
 * 不变式：一条句段的 {@link Hit} 非空 ⇔ 至少一条特征取值 > 0 ⇔ 依据文案非空。
 *
 * <p>但"句分 > 0 ⇔ Hit 非空"从 2026-10-08 起<b>不再成立</b>：那一轮新增的 22 条候选全部以 0 系数入库
 * （方向没验正就不进权重，见 {@link AigcScorer} 的 table()），所以一条句段完全可以"有依据、句分为 0"。
 * 这是有意的：依据清单是"这句话触发过哪条判据"，句分是"够不够格当成机器腔"，两件事本来就不该绑死。
 * 谁要按"有依据就算分"写下游逻辑，那是把这两层又混回去。
 */
public final class AigcFeatures {
    /** 句长变异系数低于它才算"太平"。出处：docs/oss-algorithms.md"可保留的离线特征：句长离散度"。
     *  同一段还写着"没有 logprob 就没有 burstiness 的正主"——这条只是它的粗略替身，别当成正主用。 */
    static final double FLAT_CV = 0.28d;
    /** 句首众数占比达到它才算"复读"。无外部出处，本仓库手搓，未核实。 */
    static final double OPENING_RATIO = 0.45d;
    /** 字符二元组熵低于它算局部重复表达。低熵串让指纹暴涨是 Schleimer 那篇的动机之一（docs/oss-algorithms.md）。 */
    static final double LOW_ENTROPY = 3.4d;
    /** 中文族实词多样度下限（按字去重的 type-token ratio）。 */
    static final double LOW_DIVERSITY = 0.78d;
    /** 拉丁族实词多样度下限（按词形去重）。 */
    static final double LOW_WORD_DIVERSITY = 0.62d;
    /** 熵与多样度至少要这么多有效字符，否则一句八个字必然"多样度低"。 */
    static final int MIN_SHAPE_CHARS = 24;
    /** 计分句不足这个数，整篇的句长变异系数没有意义。 */
    static final int MIN_BURST_SENTENCES = 4;
    /** 计分句不足这个数，句首众数只是巧合。 */
    static final int MIN_OPENING_SENTENCES = 5;
    /** 排比那条要看句长：超过这个长度，逗号多是正常的长句而不是排比。 */
    static final int PARALLEL_MAX_BODY = 90;

    // ---- 2026-10-08 标定轮追加的 20 条候选的口径。外部依据见 AigcFeatureId 的逐条注释与
    //      docs/oss-aigc-detection.md 第二节（humanizer-zh-academic 的 16 条中文模式 + AIGC-Detector
    //      的风险模式清单）。下面这些数都只是"把原始量压到 0..1"的刻度，不是权重；权重一律在 AigcScorer。
    //      定义一律不是从 tests/corpus/aigc-frames.txt 那份骨架表推出来的（循环论证禁止，见 AigcFeatureId）。

    /** 句式 #1：引子必须在句首这么多个字符内，理论/视角类宾语必须在句首这么多个字符内。 */
    static final int OPEN_THEORY_OBJECT_CHARS = 34;
    /** 句式 #3：编号链按**不同**编号的个数计，两个算半条、三个算满。 */
    static final int NUMBER_CHAIN_FULL = 3;
    /** 句式 #6/#13：三元对称并列的小句长度带与对称带宽（最长/最短超过它就不算"对称"）。 */
    static final int TRIPLE_MIN_CLAUSE = 4, TRIPLE_MAX_CLAUSE = 22;
    static final double TRIPLE_RATIO = 1.6d;
    /** 列表标记与冒号标签的满值命中数。 */
    static final int LIST_MARK_FULL = 2, COLON_LABEL_FULL = 2;
    /** 风格 #15 的过量方向：一句里两个「——」算满。 */
    static final int EMDASH_FULL = 2;
    /** 风格 #15 的完全不用方向：整篇至少这么多句子才谈"整篇一个都不用"，否则只是这段短。 */
    static final int DASH_FREE_MIN_SENTENCES = 8;
    /** 相邻两句长度差在较长一句的 this 比例内算"几乎等长"（句式长度对称性）。 */
    static final double ADJACENT_FLAT_BAND = 0.15d;
    static final double SYMMETRY_START = 0.34d, SYMMETRY_FULL = 0.67d;
    /** 段落内句式重复度（同型句占比）的起算线与满值线。 */
    static final double SHAPE_START = 0.30d, SHAPE_FULL = 0.70d;
    /** 文档级比例类特征共同的最少句数：不足 5 句的段落里这些占比都是巧合。 */
    static final int MIN_DOC_SHAPE_SENTENCES = 5;
    /** 句首复读（OPENING 的软版本，不设 0.45 硬门槛）的起算线与满值线。 */
    static final double ECHO_START = 0.20d, ECHO_FULL = 0.50d;
    /** 语言 #12：替代系动词占比的起算线与满值线。 */
    static final double COPULA_START = 0.45d, COPULA_FULL = 0.80d;
    /** 低罕见串比例：文档内只出现一次的字符二元组占比低于它才算"罕见串太少"。 */
    static final double RARE_START = 0.55d, RARE_FULL = 0.25d;
    /** 词表类特征的满值命中数（命中到这么多条就取满 1.0）。 */
    static final int JARGON_FULL = 3, HEDGE_FULL = 2, ATTRIBUTION_FULL = 2;
    /** 句首连接词成串的起算线与满值线（整段里多大比例的句子的句子用连接词起笔）。 */
    static final double LEAD_START = 0.34d, LEAD_FULL = 0.67d;
    /** 文内最长重复片段：低于这个字数不当证据，达到这个字数取满。 */
    static final int REPEAT_MIN_SPAN = 8, REPEAT_FULL_SPAN = 20;

    /** 句式 #1「依据/基于 XX 理论/视角/框架」起笔：引子 + 理论类宾语，两个条件都得在句首窗口内。 */
    private static final Pattern OPEN_THEORY_LEAD =
            Pattern.compile("^(?:\\s|,|、)*(?:依据|基于|根据|借助|围绕|立足|以)");
    private static final Pattern OPEN_THEORY_OBJECT = Pattern.compile(
            "(?:理论|视角|视域|视野|框架|范式|模型|方法|思路|原理|标准|指标|体系|导向"
                    + "|为(?:切入|切入点|起点|基础|支撑|核心|突破口))");

    /** 句式 #2「此案例印证了/揭示了/凸显了」段末揭示套路。 */
    private static final Pattern CLOSE_REVEAL = Pattern.compile(
            "(?:印证(?:了|出)?|揭示(?:了|出)?|凸显(?:了|出)|彰显(?:了|出)|折射出|证实(?:了|出)?"
                    + "|有力(?:地)?证明|充分(?:说明|表明|印证)|生动(?:体现|诠释|展示)|深刻(?:揭示|阐释|诠释))");

    /** 句式 #4「该处理体现了/该设计基于」被动分析套话：被分析对象 + 被动归因动词。 */
    private static final Pattern PASSIVE_TACKET = Pattern.compile(
            "(?:处理|设计|做法|方案|思路|安排|设置|布局|结构|参数|流程|机制|工艺|改造|调整|优化|案例|实例|实践)"
                    + "[^。，,；;]{0,6}(?:体现(?:了|出)?|反映(?:了|出)?|显示|源于|基于|得益(?:于)?|归因(?:于)?|对应(?:着)?|指向)");

    /** 句式 #5「面临的核心问题是」模板化问题陈述。 */
    private static final Pattern QUESTION_STUB = Pattern.compile(
            "(?:面临|面对)[^。；]{0,16}(?:核心|关键|主要|突出|共同|普遍|一系列)(?:问题|挑战|困境|难题|矛盾|痛点)"
                    + "|(?:核心|关键|首要|最大|真正)(?:问题|难点|挑战|症结)[^。；]{0,6}(?:在于|是)"
                    + "|亟待|亟需|迫在眉睫|成为[^。；]{0,10}(?:关键|重点|瓶颈|课题)");

    /** 句式 #7 + #10 段末冗余总结与泛化结论（只在自然段末句上判，位置来自 Segment.paraFinal）。 */
    private static final Pattern SUMMARY_TAIL = Pattern.compile(
            "(?:综上所述|综上|总的来[说看]|总体来[说看]|整体来[说看]|归结到底|归根结底"
                    + "|由此可[见以]|可以(?:看|推|推断)出|不难(?:发现|看出|得出)"
                    + "|具[有有][^。；]{0,8}(?:重要|显著|突出|重大|广阔|巨大)[^。；]{0,4}(?:意义|价值|作用|前景|空间)"
                    + "|奠定(?:了)?[^。；]{0,6}基础|提供(?:了)?[^。；]{0,12}(?:参考|借鉴|支撑|思路|依据|方向)"
                    + "|有待[^。；]{0,6}(?:进一步|持续)|仍(?:需|进一步|不断)[^。；]{0,8}(?:研究|验证|完善|优化|探索)"
                    + "|未来[^。；]{0,12}(?:研究|工作|探索|发展|方向)|展望)");

    /** 「X：Y」冒号标签结构（机械段落结构的主料；normalize 已把全角冒号折成半角）。 */
    private static final Pattern COLON_LABEL = Pattern.compile("[\\u4e00-\\u9fff]{2,14}:[^ 。,.]{1,24}");

    /** 列表/编号标记密度（项目符号、1)、①、（一）、第一、）。'•' 在 normalize 里就是 '-'。 */
    private static final Pattern LIST_MARK = Pattern.compile(
            "(?:^|[\\s。；;])(?:[-*]\\s|\\d{1,2}[.)]|[①-⑳]|[（(][一二三四五六七八九十]{1,3}[)）]"
                    + "|第[一二三四五六七八九十]{1,3}[、.])");

    /** 句式 #3 编号链词表。与 CN_CONNECTIVES 是两张表：那张数连接词密度，这张只数**有序编号**成串。 */
    static final String[] ORDER_MARKERS = {
        "首先", "其次", "再次", "再者", "然后", "最后", "第一", "第二", "第三", "第四",
        "其一", "其二", "其三", "一是", "二是", "三是", "四是",
    };

    /** 语言 #11 中文 AI 高频词。按 humanizer-zh-academic 第 11 条的口径手写，不抄骨架表。 */
    static final String[] AI_JARGON_TERMS = {
        "赋能", "抓手", "闭环", "颗粒度", "底层逻辑", "顶层设计", "生态", "范式", "重塑", "凸显",
        "彰显", "深度融合", "多维", "全方位", "多层次", "体系化", "精细化", "精准化", "提质增效",
        "协同", "联动", "聚力", "聚焦", "深耕", "锚定", "破解", "瓶颈", "痛点", "壁垒", "新质",
        "内生", "外延", "全生命周期", "可复制", "行稳致远", "系统性", "整体性", "协同发力",
    };

    /** 语言 #9 填充短语与过度限定。 */
    static final String[] FILLER_HEDGE_TERMS = {
        "在一定程度上", "在某种程度上", "从某种程度", "从某种意义上", "某种意义上", "不可忽视的是",
        "需要强调的是", "值得一提的是", "毋庸置疑", "不言而喻", "在特定语境", "在特定场景", "在特定条件",
        "在一般情况下", "在通常情况", "在实际操作", "在实际应用", "从实际出发", "必要的条件下",
    };

    /** 语言 #8 模糊归因：没有出处的引用。真人论文靠 [12] 这类编号引用，不靠这一套。 */
    static final String[] VAGUE_ATTRIBUTION_TERMS = {
        "有学者", "学者们", "研究表明", "研究发现", "研究显示", "相关研究", "有关研究", "多项研究",
        "大量研究", "不少研究", "诸多研究", "业内人士", "业界普遍", "普遍认为", "众所周知", "有研究认为",
    };

    /** 语言 #12 替代系动词（回避「是」）。分母是"是 + 这些替代"的全部系动词。 */
    static final String[] COPULA_SUBSTITUTES = {
        "作为", "堪称", "构成", "即是", "视为", "当作", "认定为", "成为", "表现为", "体现为",
        "转化为", "转型为", "升级为", "演化为", "发展为", "构建",
    };

    /** 「是」前面出现这些字时它是连词/副词的一部分（但是、可是、只是……），不计入系动词。 */
    private static final String COPULA_EXCLUDE_BEFORE = "但可只而或总还正亦即便";

    static final class Template {
        final String name;
        final Pattern pattern;
        Template(String name, String regex) {
            this.name = name;
            this.pattern = Pattern.compile(regex);
        }
    }

    /** 24 条模板正则：本仓库手写。依据是 MOSS 里"高频指纹是菜单和法律套话"（docs/oss-algorithms.md）——
     *  万能句在查重侧同样按文档频率停掉，在风格侧它也是最稀有的一条（真人论文实测 20.8 次/千句）。 */
    static final Template[] TEMPLATES = {
        new Template("综上所述", "综上所述|总而言之|总的来说|总体而言|概括来说"),
        new Template("值得注意的是", "值得注意的是|需要注意的是|需要指出的是|值得注意的|不难发现|由此可见|众所周知"),
        new Template("随着……的发展", "随着[^。]{0,18}的?(不断|日益|持续)?(发展|推进|深入|普及|演进)"),
        new Template("在……的背景下", "在[^。]{0,14}的(背景|形势|情况)下"),
        new Template("通过……可以发现", "通过[^。]{0,20}(可以|能够|得以|足以)?[^。]{0,6}(发现|看出|得知|得知|印证|验证)"),
        new Template("为……提供了", "为[^。]{1,18}提供(了)?[^。]{0,6}(支撑|依据|参考|借鉴|基础|思路|方向)"),
        new Template("起重要作用", "(发挥|起|起到|发挥了)了?[^。]{0,6}(重要|关键|积极|巨大)[^。]{0,3}作用"),
        new Template("具有重要意义", "具有[^。]{0,6}(重要|显著|突出|重大)[^。]{0,3}(意义|价值|作用|前景)"),
        new Template("首先……其次……最后", "首先[^。]{2,60}其次[^。]{2,60}(最后|再次|此外)"),
        new Template("一方面……另一方面", "一方面[^。]{2,60}另一方面"),
        new Template("不仅……而且", "不仅[^。]{2,50}(而且|还|更|同时|也)"),
        new Template("本文提出/认为", "(本文|本研究|本节)(认为|提出|采用|旨在|试图|尝试|围绕|聚焦)"),
        new Template("奠定坚实基础", "奠定(了)?[^。]{0,4}(坚实|良好|扎实)[^。]{0,3}基础"),
        new Template("有待进一步", "(有待|仍需|还需)[^。]{0,6}(进一步|持续)[^。]{0,4}(研究|验证|完善|提升|优化)"),
        new Template("存在广阔空间", "存在[^。]{0,6}(较大|广阔|巨大|一定)[^。]{0,4}(空间|潜力|余地|挑战)"),
        new Template("in conclusion", "in conclusion|to sum up|in summary|overall, |taken together"),
        new Template("it is worth noting", "it is worth noting|it is important to note|it should be noted|it is evident that"),
        new Template("moreover/furthermore", "moreover|furthermore|additionally|in addition|what'?s more"),
        new Template("plays a crucial role", "plays? a[^。]{0,14}(crucial|vital|important|key|pivotal|central)[^。]{0,6}role"),
        new Template("provide valuable insights", "provid?e?s? valuable insights|shed light on|offer a deep understanding"),
        new Template("this paper proposes", "this paper (proposes|presents|develops)|in this paper, |the proposed (method|approach|model|framework)"),
        new Template("in recent years", "in recent years|has been widely (used|applied|adopted|studied)|has attracted (growing|considerable) attention"),
        new Template("not only but also", "not only[^。]{2,60}but also"),
        new Template("comprehensive analysis", "comprehensive (analysis|review|investigation)|delve into|a profound impact"),
    };

    /** 中文连接词表。0.5.4 与英文表混在一起数，本版按族各用各的（族由 {@link AigcFamily} 判）。 */
    static final String[] CN_CONNECTIVES = {
        "首先", "其次", "再次", "然后", "最后", "第一", "第二", "第三", "因此", "所以", "因而", "从而", "而且", "并且",
        "此外", "另外", "同时", "不仅", "但是", "然而", "不过", "尽管", "虽然", "由于", "因为", "为了", "通过", "根据",
        "针对", "基于", "总之", "综上", "可见", "换言之", "也就是说", "总的来说", "总体而言", "需要", "应当", "必须", "能够",
        "可以",
    };

    static final String[] EN_CONNECTIVES = {
        "moreover", "furthermore", "additionally", "in addition", "however", "therefore", "thus",
        "consequently", "nevertheless", "firstly", "secondly", "finally", "in conclusion",
        "on the one hand", "on the other hand", "in contrast", "for instance",
    };

    /** 评价词/程度副词。真人论文实测一次都不触发（0/千句），所以它无法做误报校验，只当未证实的证据留着。 */
    static final String[] INTENSIFIERS = {
        "显著", "有效", "充分", "全面", "极大", "大幅", "明显", "有力", "重要", "关键", "核心", "高效",
        "稳定", "优异", "突出", "扎实", "深入", "广泛", "严格", "合理",
    };

    /** 一个打分单元 = {@link TextCorpus#sentences} 切出来、过了字数门槛的一句。 */
    public static final class Segment {
        public int start, end;            // 原串 UTF-16 偏移（与 AigcDetector.Sentence 同口径）
        public int validChars;            // TextCorpus.validCount 口径，含标点
        public int contentChars;          // 实义字符数，标点不计
        public int family;                // AigcFamily.CHINESE / LATIN / MIXED
        String body = "";                 // normalize 之后的原句切片
        String compact = "";              // body 再去空白与不可见字符
        int semicolons, dashes, quotes, commas;
        int emdashes;                   // 「——」成对出现次数（normalize 之后是 "--"），风格 #15 的过量方向
        int colons;                     // 「：」个数。冒号标签结构的主料，0.5.4 那四条标点不含它
        int stars;                      // markdown 强调 "*" 的个数（成对才算一处），风格 #16
        boolean paraFinal;              // 是不是自然段最后一句（句式 #7 段末冗余总结要看位置）

        Segment(int from, int to, String body) {
            this.start = from;
            this.end = to;
            this.body = body;
        }
    }

    /** 文档级统计：0.5.4 写在 detect() 局部变量里的那几个量，原样搬过来。 */
    public static final class DocStats {
        public double lengthCv;           // 句长变异系数，按**全部**切出的句子算（含不计分的）
        public String dominantOpening = "";
        public int dominantCount;
        public int scoredSentences;       // 过了字数门槛的句子数
        public int sentences;             // 切出来的全部句子数（含不计分的），文档级比例的分母
        public double adjacentFlatRatio;  // 相邻两句"几乎等长"的比例（句式长度对称性）
        public int docEmdashes;           // 整篇的「——」成对数（完全不用那条要看整篇）
        public double shapeRepeatRatio;   // 同型句（逗号数 + 分号数 + 句长档全同）占比
        public double copulaRatio;        // 替代系动词占全部系动词的比例（回避「是」那条）
        public double hapaxRatio;         // 只出现一次的字符二元组占比（罕见串丰富度）
        public double dominantRatio;      // 句首众数占比（OPENING_ECHO 用，不设硬门槛）
        public double leadRatio;          // 以连接词/编号起笔的句子占比（过度顺滑的逻辑链）
        public int longestRepeat;         // 文内最长重复片段字数（0 = 没有 REPEAT_MIN_SPAN 以上的重复）
    }

    /** 一条触发：特征 id + 取值 + 给人看的中文依据。文案与 0.5.4 逐字一致，旧断言按子串匹配。 */
    public static final class Hit {
        public final AigcFeatureId id;
        public double value;
        /** 一条特征可以给多条依据（模板句式命中几个就列几条，最多四条），所以这里是列表。 */
        public final ArrayList<String> evidence = new ArrayList<String>();

        Hit(AigcFeatureId id) {
            this.id = id;
        }
    }

    private AigcFeatures() { }

    /** 切一个句段并把它能一次算出的量都算好（标点计数、compact、族）。 */
    public static Segment segment(String norm, int from, int to) {
        int left = Math.max(0, Math.min(norm.length(), from));
        int right = Math.max(left, Math.min(norm.length(), to));
        String body = norm.substring(left, right);
        Segment seg = new Segment(from, to, body);
        seg.validChars = TextCorpus.validCount(norm, from, to);
        seg.contentChars = contentCount(norm, from, to);
        seg.compact = TextCorpus.compactOf(body);
        seg.family = AigcFamily.familyOf(seg.compact);
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == ';') seg.semicolons++;
            else if (c == '-') seg.dashes++;
            else if (c == '"') seg.quotes++;
            else if (c == ',') seg.commas++;
            else if (c == ':') seg.colons++;
            else if (c == '*') seg.stars++;
        }
        // 「——」在 normalize 之后是 "--"：按不重叠的成对数，一个破折号算一个。
        for (int i = 0; i + 1 < body.length(); i++) {
            if (body.charAt(i) != '-' || body.charAt(i + 1) != '-') continue;
            seg.emdashes++;
            i++;
        }
        // 自然段末句：本句之后到下一个非空白字符之间没有正文，只有换行或结尾。
        int probe = right;
        while (probe < norm.length() && (norm.charAt(probe) == ' ' || norm.charAt(probe) == '\t')) probe++;
        seg.paraFinal = probe >= norm.length() || norm.charAt(probe) == '\n' || norm.charAt(probe) == '\r';
        return seg;
    }

    public static ArrayList<Segment> segments(String norm, ArrayList<int[]> spans) {
        ArrayList<Segment> out = new ArrayList<Segment>();
        for (int i = 0; i < spans.size(); i++) {
            int[] span = spans.get(i);
            out.add(segment(norm, span[0], span[1]));
        }
        return out;
    }

    /** 字数门槛：有效字符与实义字符都要到 MIN_SENTENCE_CHARS，纯标点的短串不算一句话。 */
    public static boolean scores(Segment seg) {
        return seg != null && seg.validChars >= AigcDetector.MIN_SENTENCE_CHARS
                && seg.contentChars >= AigcDetector.MIN_SENTENCE_CHARS;
    }

    /**
     * 文档级统计。变异系数按**全部**切出的句子算（含被排除的与太短的），这是 0.5.4 的口径：
     * 一篇稿子句子长短均不均，跟哪些句子被圈成引用无关。句首众数只在计分句里数。
     */
    public static DocStats stats(String norm, ArrayList<int[]> spans, ArrayList<Segment> scored) {
        DocStats stats = new DocStats();
        stats.scoredSentences = scored == null ? 0 : scored.size();
        int total = spans.size();
        if (total == 0) return stats;
        double mean = 0d;
        double[] lengths = new double[total];
        for (int i = 0; i < total; i++) {
            int[] span = spans.get(i);
            lengths[i] = TextCorpus.validCount(norm, span[0], span[1]);
            mean += lengths[i];
        }
        mean /= total;
        double variance = 0d;
        for (int i = 0; i < total; i++) variance += (lengths[i] - mean) * (lengths[i] - mean);
        variance /= total;
        stats.lengthCv = mean <= 0d ? 0d : Math.sqrt(variance) / mean;
        // 下面四条只依赖切句结果，与哪些句子计分无关，所以放在 scored 判空之前算完。
        stats.sentences = total;
        stats.adjacentFlatRatio = adjacentFlatRatio(lengths);
        stats.docEmdashes = countEmdashes(norm, spans);
        stats.hapaxRatio = hapaxBigramRatio(norm, spans);
        stats.copulaRatio = copulaAvoidRatio(norm);
        if (scored == null) return stats;
        HashMap<String, Integer> openings = new HashMap<String, Integer>();
        for (int i = 0; i < scored.size(); i++) {
            Segment seg = scored.get(i);
            String prefix = opening(norm, seg.start, seg.end);
            if (prefix.length() == 0) continue;
            Integer previous = openings.get(prefix);
            openings.put(prefix, Integer.valueOf(previous == null ? 1 : previous.intValue() + 1));
        }
        for (Map.Entry<String, Integer> entry : openings.entrySet()) {
            if (entry.getValue().intValue() > stats.dominantCount) {
                stats.dominantCount = entry.getValue().intValue();
                stats.dominantOpening = entry.getKey();
            }
        }
        stats.dominantRatio = stats.scoredSentences <= 0 ? 0d
                : (double) stats.dominantCount / (double) stats.scoredSentences;
        stats.shapeRepeatRatio = shapeRepeatRatio(scored);
        stats.leadRatio = connectiveLeadRatio(norm, scored);
        stats.longestRepeat = longestRepeatSpan(scored);
        return stats;
    }

    /** 单条特征的取值，0..1。标定台逐条扫它，产品路径只调 {@link #of}。 */
    public static double value(AigcFeatureId id, Segment segment, DocStats stats) {
        return measure(id, segment, stats, null);
    }

    /** 一条句段的全部触发项（取值 > 0 的那些），顺序固定为 {@link AigcFeatureId#values()} 的顺序。 */
    public static ArrayList<Hit> of(Segment segment, DocStats stats) {
        ArrayList<Hit> hits = new ArrayList<Hit>();
        if (segment == null) return hits;
        // 全篇只剩空白与不可见字符时不打分：0.5.4 在同样的位置直接 return 0 分。
        if (segment.compact.length() == 0) return hits;
        AigcFeatureId[] ids = AigcFeatureId.values();
        for (int i = 0; i < ids.length; i++) {
            ArrayList<String> evidence = new ArrayList<String>();
            double value = measure(ids[i], segment, stats, evidence);
            if (value <= 0d) continue;
            Hit hit = new Hit(ids[i]);
            hit.value = value > 1d ? 1d : value;
            hit.evidence.addAll(evidence);
            hits.add(hit);
        }
        return hits;
    }

    private static double measure(AigcFeatureId id, Segment seg, DocStats stats, ArrayList<String> out) {
        switch (id) {
            case TEMPLATE: return template(seg, out);
            case CONNECTIVE: return connective(seg, out);
            case OPENING: return openingFeature(stats, out);
            case BURST: return burst(stats, out);
            case PUNCT_SEMICOLON: return semicolon(seg, out);
            case PUNCT_DASH: return dash(seg, out);
            case PUNCT_QUOTE: return quote(seg, out);
            case PARALLEL: return parallel(seg, out);
            case INTENSIFIER: return intensifier(seg, out);
            case ENTROPY: return entropy(seg, out);
            case DIVERSITY: return diversity(seg, out);
            case OPEN_THEORY: return openTheory(seg, out);
            case CLOSE_REVEAL: return closeReveal(seg, out);
            case NUMBER_CHAIN: return numberChain(seg, out);
            case PASSIVE_TACKET: return passiveTacket(seg, out);
            case QUESTION_STUB: return questionStub(seg, out);
            case PARA_SUMMARY: return paraSummary(seg, out);
            case OPENING_ECHO: return openingEcho(stats, out);
            case COLON_LABEL: return colonLabel(seg, out);
            case LIST_MARK: return listMark(seg, out);
            case TRIPLE_PARALLEL: return tripleParallel(seg, out);
            case SENT_LEN_SYMMETRY: return sentLenSymmetry(stats, out);
            case SHAPE_REPEAT: return shapeRepeat(stats, out);
            case EMDASH_OVERUSE: return emdashOveruse(seg, out);
            case EMDASH_ABSENT: return emdashAbsent(stats, out);
            case AI_JARGON: return termLadder(seg.body, AI_JARGON_TERMS, JARGON_FULL, "中文 AI 高频词密集", out);
            case FILLER_HEDGE: return termLadder(seg.body, FILLER_HEDGE_TERMS, HEDGE_FULL, "填充短语/过度限定密集", out);
            case VAGUE_ATTRIBUTION: return termLadder(seg.body, VAGUE_ATTRIBUTION_TERMS, ATTRIBUTION_FULL, "模糊归因（无出处的引用）", out);
            case COPULA_AVOID: return copulaAvoid(stats, out);
            case MARKUP_EMPHASIS: return markupEmphasis(seg, out);
            case RARE_SHAPE: return rareShape(stats, out);
            case CONNECTIVE_LEAD: return connectiveLead(seg, stats, out);
            case REPEAT_SPAN: return repeatSpan(stats, out);
            default: throw new IllegalStateException("特征 " + id + " 没有实现取值");
        }
    }

    /**
     * TEMPLATE：命中阶梯和的一半。第 1、2 条各计 1.0，第 3、4 条各 0.35，第五条起只列依据不加分，
     * 再除以 2 截到 1。除以 2 是为了让"两条套话"就是满值——0.5.4 是命中两条各加 0.30，等价于
     * 这里的取值 1.0 乘 0.60 的系数。
     */
    private static double template(Segment seg, ArrayList<String> out) {
        int matched = 0;
        double ladder = 0d;
        for (int i = 0; i < TEMPLATES.length; i++) {
            Template template = TEMPLATES[i];
            if (!template.pattern.matcher(seg.body).find()) continue;
            if (matched < 2) ladder += 1d;
            else if (matched < 4) ladder += 0.35d;
            if (matched < 4 && out != null) out.add("模板句式：" + template.name);
            matched++;
        }
        return clamp(ladder / 2d);
    }

    /**
     * CONNECTIVE：min(1, (命中数 − 1) / 3)，命中不足 2 记 0。近似 Stein 等停用词 n-gram
     * 顺序无关特征（doi:10.1002/asi.21630）的密度化。词表按族选，不再中英混数。
     */
    private static double connective(Segment seg, ArrayList<String> out) {
        int hits = countOccurrences(seg.body, connectives(seg.family));
        if (hits < 2) return 0d;
        if (out != null) out.add("连接词密度偏高");
        return clamp((hits - 1) / 3d);
    }

    static String[] connectives(int family) {
        return family == AigcFamily.LATIN ? EN_CONNECTIVES : CN_CONNECTIVES;
    }

    /**
     * OPENING：min(1, 句首众数占比 / OPENING_RATIO)，计分句不足 5 句或未达占比门槛记 0。
     * 无外部出处，本仓库手搓，未核实；实测真人论文侧 0/千句触发（0.5.4 口径），给不了误报校验。
     */
    private static double openingFeature(DocStats stats, ArrayList<String> out) {
        if (stats == null || stats.scoredSentences < MIN_OPENING_SENTENCES) return 0d;
        if (stats.dominantCount <= 0 || stats.dominantOpening.length() == 0) return 0d;
        double ratio = (double) stats.dominantCount / stats.scoredSentences;
        if (ratio < OPENING_RATIO) return 0d;
        if (out != null) out.add("句首结构重复（常以「" + stats.dominantOpening + "」开头）");
        return clamp(ratio / OPENING_RATIO);
    }

    /**
     * BURST：clamp((FLAT_CV − 句长变异系数) / FLAT_CV)，计分句不足 4 句记 0。文档级证据回灌到每一句。
     * 实测它和机写弱标签**负相关**（骨架拼接比真人更不"平"，每千句 −60.3），所以它的系数被压在节奏组最低一档。
     */
    private static double burst(DocStats stats, ArrayList<String> out) {
        if (stats == null || stats.scoredSentences < MIN_BURST_SENTENCES) return 0d;
        if (stats.lengthCv >= FLAT_CV) return 0d;
        if (out != null) out.add("句长突发度低（变异系数 " + format(stats.lengthCv) + "）");
        return clamp((FLAT_CV - stats.lengthCv) / FLAT_CV);
    }

    /** PUNCT_SEMICOLON：分号 ≥2 记 1.0，恰好 1 个记 0.6，否则 0。本仓库手搓，无出处。 */
    private static double semicolon(Segment seg, ArrayList<String> out) {
        if (seg.semicolons >= 2) {
            if (out != null) out.add("分号密集（并列长句）");
            return 1d;
        }
        if (seg.semicolons == 1) {
            if (out != null) out.add("分号衔接并列分句");
            return 0.6d;
        }
        return 0d;
    }

    /** PUNCT_DASH：破折号 ≥1 记 0.8。真人论文里 23% 的句子触发它，是最大的一块无据系数。 */
    private static double dash(Segment seg, ArrayList<String> out) {
        if (seg.dashes < 1) return 0d;
        if (out != null) out.add("破折号使用");
        return 0.8d;
    }

    /** PUNCT_QUOTE：引号 ≥4 记 0.6。 */
    private static double quote(Segment seg, ArrayList<String> out) {
        if (seg.quotes < 4) return 0d;
        if (out != null) out.add("引号密度偏高");
        return 0.6d;
    }

    /** PARALLEL：(分号 ≥1 且逗号 ≥3) 或 (逗号 ≥4 且句长 < 90) 记 1.0。 */
    private static double parallel(Segment seg, ArrayList<String> out) {
        boolean hit = (seg.semicolons >= 1 && seg.commas >= 3)
                || (seg.commas >= 4 && seg.body.length() < PARALLEL_MAX_BODY);
        if (!hit) return 0d;
        if (out != null) out.add("并列排比密集");
        return 1d;
    }

    /** INTENSIFIER：min(1, (命中数 − 1) / 4)，命中不足 2 记 0。 */
    private static double intensifier(Segment seg, ArrayList<String> out) {
        int hits = countOccurrences(seg.body, INTENSIFIERS);
        if (hits < 2) return 0d;
        if (out != null) out.add("程度副词/评价词堆叠");
        return clamp((hits - 1) / 4d);
    }

    /** ENTROPY：clamp((LOW_ENTROPY − 熵) / 1.0)，字数不足 24 记 0。 */
    private static double entropy(Segment seg, ArrayList<String> out) {
        if (seg.validChars < MIN_SHAPE_CHARS) return 0d;
        double entropy = bigramEntropy(seg.compact);
        if (entropy >= LOW_ENTROPY) return 0d;
        if (out != null) out.add("字符二元组熵偏低（" + format(entropy) + " bit，局部重复表达）");
        return clamp(LOW_ENTROPY - entropy);
    }

    /** DIVERSITY：clamp((族下限 − 多样度) / 0.2)，字数不足 24 记 0。type-token ratio 是标准度量，下限分族。 */
    private static double diversity(Segment seg, ArrayList<String> out) {
        if (seg.validChars < MIN_SHAPE_CHARS) return 0d;
        double floor = diversityFloor(seg.family);
        double diversity = diversity(seg.compact, seg.family);
        if (diversity >= floor) return 0d;
        if (out != null) out.add("实词多样度低（用词重复，" + format(diversity) + "）");
        return clamp((floor - diversity) / 0.2d);
    }

    static double diversityFloor(int family) {
        return family == AigcFamily.LATIN ? LOW_WORD_DIVERSITY : LOW_DIVERSITY;
    }

    /** 实义字符数：汉字、假名、拉丁字母与数字，标点符号不计。0.5.4 的 AigcDetector.contentCount 原样搬来。 */
    static int contentCount(String norm, int from, int to) {
        int count = 0;
        for (int i = Math.max(0, from); i < Math.min(norm.length(), to); i++) {
            char c = norm.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) count++;
            else if (c >= 0x4E00 && c <= 0x9FFF) count++;
            else if (c >= 0x3040 && c <= 0x30FF) count++;
        }
        return count;
    }

    static int countOccurrences(String body, String[] terms) {
        int hits = 0;
        for (int i = 0; i < terms.length; i++) {
            String term = terms[i];
            if (term.length() == 0) continue;
            int at = body.indexOf(term);
            while (at >= 0) {
                hits++;
                at = body.indexOf(term, at + term.length());
            }
        }
        return hits;
    }

    /** 句首结构签名：中文取前两个字，拉丁文取首个词。 */
    static String opening(String norm, int from, int to) {
        int i = Math.max(0, from);
        int limit = Math.min(norm.length(), to);
        while (i < limit && !isSignatureChar(norm.charAt(i))) i++;
        if (i >= limit) return "";
        char first = norm.charAt(i);
        StringBuilder out = new StringBuilder();
        if ((first >= 'a' && first <= 'z') || (first >= '0' && first <= '9')) {
            while (i < limit && ((norm.charAt(i) >= 'a' && norm.charAt(i) <= 'z')
                    || (norm.charAt(i) >= '0' && norm.charAt(i) <= '9'))) {
                if (out.length() < 12) out.append(norm.charAt(i));
                i++;
            }
            return out.toString();
        }
        while (i < limit && out.length() < 2) {
            if (isSignatureChar(norm.charAt(i))) out.append(norm.charAt(i));
            i++;
        }
        return out.toString();
    }

    private static boolean isSignatureChar(char c) {
        if (c >= 'a' && c <= 'z') return true;
        if (c >= '0' && c <= '9') return true;
        if (c >= 0x4E00 && c <= 0x9FFF) return true;
        return (c >= 0x3040 && c <= 0x30FF);
    }

    /** 字符二元组 Shannon 熵（bit/二元组）。 */
    static double bigramEntropy(String compact) {
        int length = compact.length();
        if (length < 3) return 0d;
        HashMap<Long, Integer> counts = new HashMap<Long, Integer>();
        int total = 0;
        for (int i = 0; i + 1 < length; i++) {
            Long key = Long.valueOf(((long) compact.charAt(i) << 16) | compact.charAt(i + 1));
            Integer previous = counts.get(key);
            counts.put(key, Integer.valueOf(previous == null ? 1 : previous.intValue() + 1));
            total++;
        }
        double entropy = 0d;
        for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
            double p = (double) entry.getValue().intValue() / total;
            entropy -= p * log2(p);
        }
        return entropy;
    }

    /** 实词多样度：拉丁族按词形去重，中文族按字去重。 */
    static double diversity(String compact, int family) {
        int total = 0;
        if (family == AigcFamily.LATIN) {
            HashSet<String> words = new HashSet<String>();
            StringBuilder word = new StringBuilder();
            for (int i = 0; i < compact.length(); i++) {
                char c = compact.charAt(i);
                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) word.append(c);
                else if (word.length() > 0) {
                    words.add(word.toString());
                    total++;
                    word.setLength(0);
                }
            }
            if (word.length() > 0) {
                words.add(word.toString());
                total++;
            }
            return total == 0 ? 0d : (double) words.size() / total;
        }
        HashSet<Character> seen = new HashSet<Character>();
        for (int i = 0; i < compact.length(); i++) {
            seen.add(Character.valueOf(compact.charAt(i)));
            total++;
        }
        return total == 0 ? 0d : (double) seen.size() / total;
    }

    // ---- 2026-10-08 追加的 20 条候选特征。取值一律 0..1，口径与外部依据逐条写在方法上。 ----

    /**
     * OPEN_THEORY（句式 #1「依据/基于 XX 理论」起笔）：句首引子 + 句首窗口内的理论类宾语，命中记 1.0。
     * 依据：docs/oss-aigc-detection.md 第二节内容层 #1。
     */
    private static double openTheory(Segment seg, ArrayList<String> out) {
        String body = seg.body;
        if (body.length() == 0 || !OPEN_THEORY_LEAD.matcher(body).find()) return 0d;
        String head = body.substring(0, Math.min(body.length(), OPEN_THEORY_OBJECT_CHARS));
        if (!OPEN_THEORY_OBJECT.matcher(head).find()) return 0d;
        if (out != null) out.add("以理论/视角/框架起笔（句式套路）");
        return 1d;
    }

    /** CLOSE_REVEAL（句式 #2「此案例印证了/揭示了」段末套路）：命中即 1.0，同一条证据不叠加。 */
    private static double closeReveal(Segment seg, ArrayList<String> out) {
        if (!CLOSE_REVEAL.matcher(seg.body).find()) return 0d;
        if (out != null) out.add("「印证了/揭示了」式揭示套路");
        return 1d;
    }

    /** NUMBER_CHAIN（句式 #3 编号逻辑链）：同句内**不同**编号标记的个数，2 个记 0.5，3 个记满。 */
    private static double numberChain(Segment seg, ArrayList<String> out) {
        int distinct = 0;
        for (int i = 0; i < ORDER_MARKERS.length; i++) {
            if (seg.body.indexOf(ORDER_MARKERS[i]) >= 0) distinct++;
        }
        if (distinct < 2) return 0d;
        if (out != null) out.add("编号逻辑链成串（首先/其次/再次…）");
        return clamp((distinct - 1) / (double) (NUMBER_CHAIN_FULL - 1));
    }

    /** PASSIVE_TACKET（句式 #4「该处理体现了/该设计基于」被动分析套话）：命中即 1.0。 */
    private static double passiveTacket(Segment seg, ArrayList<String> out) {
        if (!PASSIVE_TACKET.matcher(seg.body).find()) return 0d;
        if (out != null) out.add("被动分析套话（该处理体现了…）");
        return 1d;
    }

    /** QUESTION_STUB（句式 #5「面临的核心问题是」模板化问题陈述）：命中即 1.0。 */
    private static double questionStub(Segment seg, ArrayList<String> out) {
        if (!QUESTION_STUB.matcher(seg.body).find()) return 0d;
        if (out != null) out.add("模板化问题陈述（面临的核心问题是…）");
        return 1d;
    }

    /**
     * PARA_SUMMARY（句式 #7 段末冗余总结 + #10 泛化结论）：只有自然段末句才判——位置本身就是证据。
     * 命中 1 条记 0.7，2 条及以上记满。
     */
    private static double paraSummary(Segment seg, ArrayList<String> out) {
        if (!seg.paraFinal) return 0d;
        java.util.regex.Matcher matcher = SUMMARY_TAIL.matcher(seg.body);
        int hits = 0;
        while (matcher.find()) hits++;
        if (hits == 0) return 0d;
        if (out != null) out.add("段末冗余总结/泛化结论");
        return hits >= 2 ? 1d : 0.7d;
    }

    /**
     * OPENING_ECHO（AIGC-Detector 风险模式"重复句首"）：OPENING 的软版本。OPENING 那条 0.45 的硬门槛
     * 在带标注语料上两侧都是 0 触发（docs/aigc-corpus.md 第九节），等于没有语料可审；这条不设硬门槛，
     * 直接把句首众数占比按 ECHO_START..ECHO_FULL 拉成连续取值。文档级。
     */
    private static double openingEcho(DocStats stats, ArrayList<String> out) {
        if (stats == null || stats.scoredSentences < MIN_DOC_SHAPE_SENTENCES) return 0d;
        if (stats.dominantRatio <= ECHO_START) return 0d;
        if (out != null) out.add("句首结构复读（常以「" + stats.dominantOpening + "」开头）");
        return clamp((stats.dominantRatio - ECHO_START) / (ECHO_FULL - ECHO_START));
    }

    /** COLON_LABEL「X：Y；Z：W」冒号标签结构密度：每满 COLON_LABEL_FULL 个标签记一档。 */
    private static double colonLabel(Segment seg, ArrayList<String> out) {
        if (seg.colons < 1) return 0d;
        int labels = 0;
        java.util.regex.Matcher matcher = COLON_LABEL.matcher(seg.body);
        while (matcher.find()) labels++;
        if (labels < 1) return 0d;
        if (out != null) out.add("「X：Y」标签式结构密集");
        return clamp((double) labels / (double) COLON_LABEL_FULL);
    }

    /** LIST_MARK 列表/编号标记密度（机械段落结构：项目符号、①②、1)/一、）。 */
    private static double listMark(Segment seg, ArrayList<String> out) {
        int marks = 0;
        java.util.regex.Matcher matcher = LIST_MARK.matcher(seg.body);
        while (matcher.find()) marks++;
        if (marks < 1) return 0d;
        if (out != null) out.add("列表/编号标记密集");
        return clamp((double) marks / (double) LIST_MARK_FULL);
    }

    /**
     * TRIPLE_PARALLEL（句式 #6 高度对称的三元并列 + #13 过度对仗的排比）：连续三个小句长度都在
     * [TRIPLE_MIN_CLAUSE, TRIPLE_MAX_CLAUSE] 且最长/最短不超过 TRIPLE_RATIO。一组记 0.7，两组起记满。
     * 与 PARALLEL 的区别：PARALLEL 只数逗号个数（真人稿 222/千句，方向实测为反），这条要的是**长度对称**。
     */
    private static double tripleParallel(Segment seg, ArrayList<String> out) {
        ArrayList<Integer> lens = clauseLengths(seg.body);
        int runs = 0;
        for (int i = 0; i + 2 < lens.size(); i++) {
            int a = lens.get(i).intValue(), b = lens.get(i + 1).intValue(), c = lens.get(i + 2).intValue();
            if (a < TRIPLE_MIN_CLAUSE || b < TRIPLE_MIN_CLAUSE || c < TRIPLE_MIN_CLAUSE) continue;
            if (a > TRIPLE_MAX_CLAUSE || b > TRIPLE_MAX_CLAUSE || c > TRIPLE_MAX_CLAUSE) continue;
            int low = Math.min(a, Math.min(b, c));
            int high = Math.max(a, Math.max(b, c));
            if (low <= 0 || high > TRIPLE_RATIO * low) continue;
            runs++;
        }
        if (runs == 0) return 0d;
        if (out != null) out.add("三元对称并列句式");
        return runs >= 2 ? 1d : 0.7d;
    }

    /** SENT_LEN_SYMMETRY（句式长度对称性）：BURST 的姊妹证据。BURST 量的是总体离散度，这条量"相邻句是否几乎等长"。 */
    private static double sentLenSymmetry(DocStats stats, ArrayList<String> out) {
        if (stats == null || stats.sentences < MIN_DOC_SHAPE_SENTENCES) return 0d;
        if (stats.adjacentFlatRatio <= SYMMETRY_START) return 0d;
        if (out != null) out.add("句式长度对称（相邻句几乎等长 " + format(stats.adjacentFlatRatio) + "）");
        return clamp((stats.adjacentFlatRatio - SYMMETRY_START) / (SYMMETRY_FULL - SYMMETRY_START));
    }

    /** SHAPE_REPEAT 段落内句式重复度：同一段里"逗号数 + 分号数 + 句长档"完全同型的句子占比。文档级。 */
    private static double shapeRepeat(DocStats stats, ArrayList<String> out) {
        if (stats == null || stats.scoredSentences < MIN_DOC_SHAPE_SENTENCES) return 0d;
        if (stats.shapeRepeatRatio <= SHAPE_START) return 0d;
        if (out != null) out.add("段落内句式重复（同型句占比 " + format(stats.shapeRepeatRatio) + "）");
        return clamp((stats.shapeRepeatRatio - SHAPE_START) / (SHAPE_FULL - SHAPE_START));
    }

    /** EMDASH_OVERUSE（风格 #15 的"过量"方向）：一句里「——」成对数，2 处记满。 */
    private static double emdashOveruse(Segment seg, ArrayList<String> out) {
        if (seg.emdashes < 1) return 0d;
        if (out != null) out.add("「——」破折号密集（" + seg.emdashes + " 处）");
        return clamp((double) seg.emdashes / (double) EMDASH_FULL);
    }

    /**
     * EMDASH_ABSENT（风格 #15 的"完全不用"方向）：整篇一个破折号都没有。这是"过量"的反面，
     * 所以它必须是一条**独立**的正取值特征，而不是把 PUNCT_DASH 的系数配成负数——
     * AigcScorer.Coefficients.set 拒绝负系数，方向一律在特征定义里解决，不进权重。文档级。
     */
    private static double emdashAbsent(DocStats stats, ArrayList<String> out) {
        if (stats == null || stats.sentences < DASH_FREE_MIN_SENTENCES) return 0d;
        if (stats.docEmdashes > 0) return 0d;
        if (out != null) out.add("整篇不用破折号（句式过于顺滑）");
        return 1d;
    }

    /** 词表类特征的共同口径：命中不足 2 条记 0（沿用 CONNECTIVE/INTENSIFIER 的纪律），满值命中数分族共用。 */
    private static double termLadder(String body, String[] terms, int full, String label, ArrayList<String> out) {
        int hits = countOccurrences(body, terms);
        if (hits < 2) return 0d;
        if (out != null) out.add(label);
        return clamp((double) (hits - 1) / (double) Math.max(1, full - 1));
    }

    /** COPULA_AVOID（语言 #12 主动回避「是」）：替代系动词占全部系动词的比例。文档级。 */
    private static double copulaAvoid(DocStats stats, ArrayList<String> out) {
        if (stats == null || stats.copulaRatio <= COPULA_START) return 0d;
        if (out != null) out.add("回避系动词「是」（替代式占比 " + format(stats.copulaRatio) + "）");
        return clamp((stats.copulaRatio - COPULA_START) / (COPULA_FULL - COPULA_START));
    }

    /** MARKUP_EMPHASIS（风格 #16 加粗滥用）：markdown 强调成对数。docx 正文里没有这层标记，取值多为 0。 */
    private static double markupEmphasis(Segment seg, ArrayList<String> out) {
        int pairs = seg.stars / 2;
        if (pairs < 1) return 0d;
        if (out != null) out.add("加粗/强调标记密集（" + pairs + " 处）");
        return clamp((double) pairs);
    }

    /**
     * RARE_SHAPE 低罕见串比例：文档内只出现一次的字符二元组占比低于 RARE_START 才亮灯。
     * 依据：docs/oss-aigc-detection.md 表里 GLTR 那条"按 token rank 分箱"的口径——本仓库离线没有词频表，
     * 用文档内 hapax 比例当替身（这是替身，不是 GLTR 本体，别当成困惑度用）。文档级。
     */
    private static double rareShape(DocStats stats, ArrayList<String> out) {
        if (stats == null || stats.sentences < MIN_DOC_SHAPE_SENTENCES) return 0d;
        if (stats.hapaxRatio <= 0d || stats.hapaxRatio >= RARE_START) return 0d;
        if (out != null) out.add("罕见串比例偏低（只出现一次的二元组 " + format(stats.hapaxRatio) + "）");
        return clamp((RARE_START - stats.hapaxRatio) / (RARE_START - RARE_FULL));
    }

    // ---- 上面那批特征的公共算量 ----

    /** 相邻两句"几乎等长"的比例：|a-b| <= ADJACENT_FLAT_BAND x max(a,b)。分母是全部相邻对。 */
    static double adjacentFlatRatio(double[] lengths) {
        if (lengths == null || lengths.length < 2) return 0d;
        int pairs = lengths.length - 1;
        int flat = 0;
        for (int i = 0; i + 1 < lengths.length; i++) {
            double a = lengths[i], b = lengths[i + 1];
            double high = Math.max(a, b);
            if (high <= 0d) continue;
            if (Math.abs(a - b) <= ADJACENT_FLAT_BAND * high) flat++;
        }
        return (double) flat / (double) pairs;
    }

    /** 整篇的「——」成对数（跨句累加，按不重叠的成对数）。 */
    static int countEmdashes(String norm, ArrayList<int[]> spans) {
        int total = 0;
        for (int i = 0; i < spans.size(); i++) {
            int[] span = spans.get(i);
            int from = Math.max(0, span[0]);
            int to = Math.min(norm.length(), span[1]);
            for (int k = from; k + 1 < to; k++) {
                if (norm.charAt(k) != '-' || norm.charAt(k + 1) != '-') continue;
                total++;
                k++;
            }
        }
        return total;
    }

    /**
     * CONNECTIVE_LEAD（AIGC-Detector"过度顺滑的逻辑"）：整段里以连接词/编号起笔的句子占比。
     * 词表沿用 CN_CONNECTIVES / EN_CONNECTIVES（族内自取），不新造第三张连接词表。文档级。
     * 注意：这张表与骨架表 TOKEN 有交集，标定台 OVERLAP 行会把交集逐条点名；拟合只用 M-RAW + H1/H2/H3，
     * M2（拿骨架拼出来的那份）从头到尾不是拟合输入，所以这条不会变成循环论证。
     */
    private static double connectiveLead(Segment seg, DocStats stats, ArrayList<String> out) {
        if (stats == null || stats.leadRatio <= 0d || stats.scoredSentences < MIN_DOC_SHAPE_SENTENCES) return 0d;
        if (stats.leadRatio <= LEAD_START) return 0d;
        if (out != null) out.add("句首连接词成串（" + format(stats.leadRatio) + " 的句子以连接词起笔）");
        return clamp((stats.leadRatio - LEAD_START) / (LEAD_FULL - LEAD_START));
    }

    /**
     * REPEAT_SPAN 文内最长重复片段：整篇里在**不同位置**重复出现的最长连续串（compact 口径，跨句也算）。
     * 依据：docs/oss-aigc-detection.md 表里 DNA-GPT 那一类"子串重复/分支熵"的做法，与 docs/oss-algorithms.md
     * 里"长串重复才可疑"的同一条查重纪律。这是离线替身，不是 DNA-GPT 本体。文档级。
     */
    private static double repeatSpan(DocStats stats, ArrayList<String> out) {
        if (stats == null || stats.longestRepeat < REPEAT_MIN_SPAN) return 0d;
        if (out != null) out.add("文内最长重复片段 " + stats.longestRepeat + " 字");
        return clamp((stats.longestRepeat - REPEAT_MIN_SPAN) / (double) (REPEAT_FULL_SPAN - REPEAT_MIN_SPAN));
    }

    /** 最长重复片段的朴素求法：从长到短试 n 元串，第一个在不同位置出现过两次以上的长度就是答案。 */
    static int longestRepeatSpan(ArrayList<Segment> scored) {
        if (scored == null || scored.size() < 2) return 0;
        StringBuilder all = new StringBuilder();
        ArrayList<Integer> starts = new ArrayList<Integer>();
        ArrayList<Integer> lengths = new ArrayList<Integer>();
        for (int i = 0; i < scored.size(); i++) {
            String compact = TextCorpus.compactOf(scored.get(i).body);
            starts.add(Integer.valueOf(all.length()));
            lengths.add(Integer.valueOf(compact.length()));
            all.append(compact);
        }
        String text = all.toString();
        for (int n = REPEAT_FULL_SPAN; n >= REPEAT_MIN_SPAN; n--) {
            HashMap<String, Integer> seen = new HashMap<String, Integer>();
            for (int i = 0; i + n <= text.length(); i++) {
                String key = text.substring(i, i + n);
                Integer previous = seen.get(key);
                if (previous == null) {
                    seen.put(key, Integer.valueOf(i));
                    continue;
                }
                if (spansDiffer(starts, lengths, previous.intValue(), i, n)) return n;
            }
        }
        return 0;
    }

    /** 两次出现必须真的来自不同处：完全重叠的同一次出现不算重复。 */
    private static boolean spansDiffer(ArrayList<Integer> starts, ArrayList<Integer> lengths, int a, int b, int n) {
        if (Math.abs(a - b) >= n) return true;
        for (int s = 0; s < starts.size(); s++) {
            int from = starts.get(s).intValue();
            int to = from + lengths.get(s).intValue();
            boolean inA = a >= from && a + n <= to;
            boolean inB = b >= from && b + n <= to;
            if (!(inA && inB)) return true;
        }
        return false;
    }

    /** 只出现一次的字符二元组占二元组**种类数**的比例。按句取 compact，不跨句拼二元组。 */
    static double hapaxBigramRatio(String norm, ArrayList<int[]> spans) {
        HashMap<Long, Integer> counts = new HashMap<Long, Integer>();
        for (int i = 0; i < spans.size(); i++) {
            int[] span = spans.get(i);
            String compact = TextCorpus.compactOf(
                    norm.substring(Math.max(0, span[0]), Math.min(norm.length(), span[1])));
            for (int k = 0; k + 1 < compact.length(); k++) {
                Long key = Long.valueOf(((long) compact.charAt(k) << 16) | compact.charAt(k + 1));
                Integer previous = counts.get(key);
                counts.put(key, Integer.valueOf(previous == null ? 1 : previous.intValue() + 1));
            }
        }
        if (counts.isEmpty()) return 0d;
        int hapax = 0;
        for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
            if (entry.getValue().intValue() == 1) hapax++;
        }
        return (double) hapax / (double) counts.size();
    }

    /** 替代系动词占全部系动词的比例。"但是/可是/只是…"里的「是」不算系动词。 */
    static double copulaAvoidRatio(String norm) {
        int shi = 0;
        for (int i = 0; i < norm.length(); i++) {
            if (norm.charAt(i) != '是') continue;
            if (i > 0 && COPULA_EXCLUDE_BEFORE.indexOf(norm.charAt(i - 1)) >= 0) continue;
            shi++;
        }
        int substitutes = countOccurrences(norm, COPULA_SUBSTITUTES);
        int total = shi + substitutes;
        return total <= 0 ? 0d : (double) substitutes / (double) total;
    }

    /** 同型句占比：签名相同的句子占计分句的比例（只算出现在两次及以上的那部分）。 */
    static double shapeRepeatRatio(ArrayList<Segment> scored) {
        if (scored == null || scored.size() < MIN_DOC_SHAPE_SENTENCES) return 0d;
        HashMap<String, Integer> counts = new HashMap<String, Integer>();
        for (int i = 0; i < scored.size(); i++) {
            String key = shapeSignature(scored.get(i));
            Integer previous = counts.get(key);
            counts.put(key, Integer.valueOf(previous == null ? 1 : previous.intValue() + 1));
        }
        int repeated = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue().intValue() >= 2) repeated += entry.getValue().intValue();
        }
        return (double) repeated / (double) scored.size();
    }

    /** 以连接词/编号起笔的句子占比：句子前两个非空白字符命中本族的连接词表或编号表。 */
    static double connectiveLeadRatio(String norm, ArrayList<Segment> scored) {
        if (scored == null || scored.isEmpty()) return 0d;
        int leads = 0;
        for (int i = 0; i < scored.size(); i++) {
            Segment seg = scored.get(i);
            String head = seg.body.length() <= 4 ? seg.body : seg.body.substring(0, 4);
            String[] table = connectives(seg.family);
            boolean hit = false;
            for (int k = 0; k < table.length; k++) {
                if (head.startsWith(table[k])) { hit = true; break; }
            }
            if (!hit) {
                for (int k = 0; k < ORDER_MARKERS.length; k++) {
                    if (head.startsWith(ORDER_MARKERS[k])) { hit = true; break; }
                }
            }
            if (hit) leads++;
        }
        return (double) leads / (double) scored.size();
    }

    /** 句式签名：逗号数（截到 5）+ 分号数（截到 2）+ 句长档（<20 / <45 / 其余）。粗到能重复，细到能区分长短句。 */
    static String shapeSignature(Segment seg) {
        int commas = Math.min(5, seg.commas);
        int semicolons = Math.min(2, seg.semicolons);
        int length = seg.body.length();
        int bucket = length < 20 ? 0 : (length < 45 ? 1 : 2);
        return commas + ":" + semicolons + ":" + bucket;
    }

    /** 逗号/分号切出的小句长度表（去掉空白后的字符数），供 TRIPLE_PARALLEL 找对称三元组。 */
    static ArrayList<Integer> clauseLengths(String body) {
        ArrayList<Integer> out = new ArrayList<Integer>();
        int run = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == ',' || c == ';' || c == '.' || c == '!' || c == '?' || c == ':') {
                if (run > 0) out.add(Integer.valueOf(run));
                run = 0;
                continue;
            }
            if (Character.isWhitespace(c)) continue;
            run++;
        }
        if (run > 0) out.add(Integer.valueOf(run));
        return out;
    }

    private static final double LN2 = Math.log(2d);

    static double log2(double value) {
        return value <= 0d ? 0d : Math.log(value) / LN2;
    }

    static String format(double value) {
        return String.format(java.util.Locale.US, "%.2f", Double.valueOf(value));
    }

    private static double clamp(double value) {
        if (value < 0d) return 0d;
        return value > 1d ? 1d : value;
    }
}
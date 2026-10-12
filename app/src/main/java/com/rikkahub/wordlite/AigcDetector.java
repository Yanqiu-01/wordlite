package com.rikkahub.wordlite;

import java.util.ArrayList;

/**
 * 本机 AIGC 倾向检测的编排层：切句 → 取特征值（{@link AigcFeatures}）→ 按自然段算节奏读数
 * （{@link AigcRhythm}）→ 相邻同腔句并成区间 → 双门槛定档 → 按实测精度定置信档（{@link AigcConfidence}）。
 * 这一层一个权重都不写，也不再自己量文本。
 *
 * <p>两条口径写死了不许动：
 * 1. {@link Result#rate} 仍然是**字符加权的均值**（Σ 段分 × 有效字符 / Σ 有效字符），权重口径与
 *    0.7.x 一致。区间是"分区不挑选"的视图——每个计分句恰好落进一个区间，低分句也自成区间——于是
 *    Σ 区间分 × 字数 / Σ 字数 == rate 是恒等式，聚合只改视图，一个字符都没被重复计或漏掉。
 * 2. 有效字符不足 {@link #MIN_DOCUMENT_CHARS} 只给"样本不足"，不给指数。0 个计分句同样算样本不足。
 *
 * <p><b>2026-10-12 起分数由谁给。</b>段分来自 {@link AigcRhythm}：随包判据表驱动的 19 条量
 * （15 条节奏/结构 + 4 条用词统计，后者与"句子怎么切"无关，专补"并句就能躲"那一刀），
 * 定标库是同语域配对（真人原文 vs 同选题模型稿），段级 AUC 0.930、整篇 0.982、按真人 P99 划线误报 1.11%
 * （以上三个数与真人三个刻度、判定门槛一律读表，代码里那份常量只是表缺失时的兜底）。
 * <b>套话词表不进分数</b>：{@link AigcTells} 那批条目在论文正文里真人和机器都是 0 命中，只在真人
 * 正式摘要里密集出现（实测 45.9 次/千字），拿它当分数等于量"像不像摘要"；{@link AigcScorer} 那套手写
 * 启发式在标注语料上方向是反的（AUC 0.312）。两样都照样出证据、照样出降重建议，就是不计分。
 * 过程与全部数字见 docs/aigc-rhythm.md 与 docs/aigc-detection.md 第九节。
 *
 * <p><b>刻度与披露。</b>段分是一条相对刻度，不是百分比，也不夹进 0~100：定标库里真人论文正文段落分
 * 中位 0.45、P90 2.96、P99 4.56（这三个数就在表头，界面印的就是它们）。所以界面固定给四样：① AI 倾向指数 + 真人三个刻度；
 * ② 疑似机器生成字数与占比（段分过真人 P99 那段的字数，并写明这一线的真人误报率）；③ 可疑段落列表
 * （能点回正文，每段带上贡献最大的几条判据）；④ 一行出处（仪器版本 + 实测 AUC 与误报 + 置信档 +
 * "这是本机模型给的复核线索，不是判决"）。标定不够的数照样印，但必须自带 {@link AigcConfidence#LOW}
 * 那句"只作复核排序线索"——用整格空白代替说明，等于把功能关掉。
 *
 * <p>加成只进档位判定（{@link Tier}），不进任何百分比：rate、flaggedChars、Segment.score 三者口径互不打架。
 */
public final class AigcDetector {
    /** 有效字符数少于该值的句子不计分。 */
    public static final int MIN_SENTENCE_CHARS = 8;
    /** 低于这个有效字符数只给特征提示，不给指数。 */
    public static final int MIN_DOCUMENT_CHARS = 400;
    /**
     * 相邻两句都要到这条线才并段。新尺度下它的含义跟着变了：真人段落分的中位在 0.45 上下，
     * 0.35 就落在"比一篇真人论文的典型段落更像机器"一点点的位置，只用来决定"这一段并成一个区间展示"，
     * 不参与判可疑（判可疑看 {@link AigcRhythm#humanP99()}）。
     */
    public static final float SEGMENT_SCORE_FLOOR = 0.35f;
    /**
     * 区间分过这条线才算可疑区间，{@code flaggedChars} 由它来。
     *
     * <p>2026-10-12 起判可疑真正的线是**真人段落分 P99**（当前表里是 4.5585，见 {@link AigcRhythm}），
     * 这条 0.45 只是 {@link CharLedger} 与 {@link AigcScorer#check} 共用的下限：过线的区间分必然远大于它，
     * 两边的账不会打架。常量本身不许删——{@link CharLedger#FLAG_SCORE_GATE} 直接引用它。
     */
    public static final float SEGMENT_FLAG_GATE = 0.45f;
    /** 一个区间最多并几句、最多多少有效字符：WCopyfind"按绝对量而不是只看比例"的同一条纪律。 */
    public static final int MAX_SEGMENT_SENTENCES = 6;
    public static final int MAX_SEGMENT_CHARS = 1200;
    /** 档位的双门槛：可疑字数是绝对量，最长可疑区间的句数是连续度，两个一起看。 */
    public static final int TIER_STRONG_CHARS = 400, TIER_REVIEW_CHARS = 200;
    public static final int TIER_STRONG_RUN = 3, TIER_REVIEW_RUN = 2;
    /** 出处行末尾那一句，报告、界面、HTML 三处同一个字。 */
    public static final String NOT_A_VERDICT = "这是本机模型给的复核线索，不是判决。";
    /** 可疑字数为 0 时的说法。只能写"未发现"，不许写"没有 AI 生成"，也不许写"通过"。 */
    public static final String NOT_FOUND = "未发现可疑段落";

    /** 置信档位。顺序即强弱，报告与断言按 ordinal 比高低。 */
    public enum Tier {
        /** 可比对字数不够，只列特征。 */
        INSUFFICIENT_SAMPLE,
        /** 未见明显机器腔。 */
        NONE,
        /** 有痕迹但规模或连续度不足以下判断。 */
        WATCH,
        /** 成片的机器腔，值得复核。 */
        NEEDS_REVIEW,
        /** 成片且量大，整节复核。 */
        STRONG
    }

    public static final class Sentence {
        public int start, end;                   // 原串偏移
        public float score;                      // 所在那一段的节奏读数（段级性质，可正可负）
        public final ArrayList<String> features = new ArrayList<String>(); // 中文判据名
    }

    /** 一个被判可疑的自然段：界面上"点回正文"跳的就是它，criteria 是它可疑的理由。 */
    public static final class Paragraph {
        public int start, end;
        public double score;
        public int sentences;
        public int chars;
        public final ArrayList<String> criteria = new ArrayList<String>();
    }

    /** 分区视图：成员句的并集，不扩边；每个计分句恰好属于一个区间。 */
    public static final class Segment {
        public int start, end;                   // 成员句的并集偏移（不扩边）
        public int fromSentence, toSentence;     // 在 Result.sentences 里的下标闭区间
        public int chars;                        // 成员句有效字符之和（不是 UTF-16 跨度）
        public float score;                      // 成员句分的字符加权平均，不含任何加成
        public int family;                       // AigcFamily.CHINESE / LATIN / MIXED
        public boolean flagged;                  // 所在段落过了真人 P99 那条可疑线
        /** 与查重命中重叠的字数，由查重侧回填（0.7.2 口径互扣，见 artifacts/research/aigc-handoff.md）。 */
        public int duplicatedChars;
        public final ArrayList<String> evidence = new ArrayList<String>(); // 成员句依据去重合并
    }

    public static final class Result {
        public final ArrayList<Sentence> sentences = new ArrayList<Sentence>();
        /** 计分句的分区视图：按原文顺序、互不重叠、无缝覆盖全部计分句。 */
        public final ArrayList<Segment> segments = new ArrayList<Segment>();
        /** 判可疑的自然段，按原文顺序；列表点回正文用的就是它。 */
        public final ArrayList<Paragraph> suspiciousParagraphs = new ArrayList<Paragraph>();
        public float rate;                       // 定义不变：Σ 段分×有效字符 / Σ 有效字符
        public int comparedChars;
        /** 引用区间与参考文献表里没参与打分的字数。 */
        public int excludedChars;
        /** 可疑段落的有效字符之和（不是 UTF-16 跨度，代理对不再算两个）。 */
        public int flaggedChars;
        /** 可疑区间的个数与其中最长的句数/字数，档位判定的依据，不是黑箱。 */
        public int flaggedSegments, longestRunSentences, longestRunChars;
        public Tier tier = Tier.INSUFFICIENT_SAMPLE;
        /** 有效字符不足 MIN_DOCUMENT_CHARS，指数不可信。 */
        public boolean insufficientSample;
        /** 一句人话结论：档位 + 指数 + 占比 + 出处，样本不足时只说样本不足。 */
        public String verdict = "";
        /** 本次列证据用的系数表（族取中文族；拉丁族的表由它平移而来）。只用于命名证据，不参与打分。 */
        public AigcScorer.Coefficients coefficients;
        public String coefficientVersion = "";
        /** 本次有多少句由离线模型打分、其中多少句落进平滑窗口。模型没装载或未达标时恒为 0。 */
        public int modelSentences;
        public int windowHits;

        /* ---- 分级披露：这几样是"数从哪件仪器来、刻度在哪"的账，界面上四样全靠它 ---- */
        /** 参与打分那件仪器的置信档；没实测读数的一律 LOW。 */
        public AigcConfidence confidence = AigcConfidence.LOW;
        /** 本次真正给分的那件仪器（版本号在内）。 */
        public String instrument = "";
        /** 一行出处：仪器与版本、实测 AUC 与误报、置信档、末尾那句"不是判决"。 */
        public String provenance = "";
        /** 词表类证据下面必须跟着的那一句（只有真列了词表证据才非空）。 */
        public String evidenceCaveat = "";
        /** 节奏判据的覆盖情况：只有这些段落真算出了分布，其余段落太短或表没装载。 */
        public int rhythmParagraphs, rhythmSentences, paragraphsTotal;
        /** 达到判定规模（{@link AigcRhythm#flagChars()} 字且 {@link AigcRhythm#flagSentences()} 句）的段落数。 */
        public int judgeableParagraphs;
        /** 节奏判据往机器方向亮灯的次数与词表类规则命中次数（后者只列证据不计分）。 */
        public int rhythmTriggers, tellHits;
        /** 计分句里拉丁族（整句英文那一类）占的有效字符：判据表在中文论文正文上定标，这一栏决定指数成不成立。 */
        public int latinChars;
        /** AI 味特征密度：每千有效字多少次（节奏判据亮灯次数 + 词表命中次数）。 */
        public double densityPer1000 = Double.NaN;
        /** 真人论文正文的三把刻度：指数要跟它们并排显示才知道自己在哪。 */
        public double scaleP50 = Double.NaN, scaleP90 = Double.NaN, scaleP99 = Double.NaN;
        /** 划线那一档的真人误报率（每千段），出处行必须带它。 */
        public double falsePositivesPerMille = AigcRhythm.MEASURED_FALSE_POSITIVES_PER_MILLE;
        /** 词表类命中区间（含 cap 与 hint），降重工单与证据列表都读它。 */
        public final ArrayList<AigcTells.Hit> tells = new ArrayList<AigcTells.Hit>();
    }

    private AigcDetector() { }

    /** 逐段给出节奏读数，整篇指数按有效字符加权；短于 MIN_SENTENCE_CHARS 的句子不参与。 */
    public static Result detect(String text) { return detect(text, null); }

    /** excludedSpans 圈住的是抄来的引用与结构文本，不拿来判机器腔。 */
    public static Result detect(String text, int[] excludedSpans) {
        return detectInto(text, TextCorpus.mergeSpans(excludedSpans, text == null ? 0 : text.length()));
    }

    private static Result detectInto(String text, int[] excluded) {
        Result result = new Result();
        ArrayList<AigcFeatures.Segment> scored = new ArrayList<AigcFeatures.Segment>();
        ArrayList<Integer> paragraphs = new ArrayList<Integer>();
        if (text != null && text.length() > 0) {
            String norm = TextCorpus.normalize(text);
            ArrayList<int[]> spans = TextCorpus.sentences(text);
            int[] lineBreaks = lineBreakOffsets(text);
            int line = 0;
            int totalChars = 0;
            for (int i = 0; i < spans.size(); i++) {
                int[] span = spans.get(i);
                while (line < lineBreaks.length && lineBreaks[line] < span[0]) line++;
                if (TextCorpus.insideSpan(excluded, span[0])) {
                    result.excludedChars += TextCorpus.validCount(norm, span[0], span[1]);
                    continue;
                }
                AigcFeatures.Segment seg = AigcFeatures.segment(norm, span[0], span[1]);
                // 只有标点的短串不算一句话：有效字符或实义字符不足 MIN_SENTENCE_CHARS 都不计分。
                if (!AigcFeatures.scores(seg)) continue;
                scored.add(seg);
                paragraphs.add(Integer.valueOf(line));
                totalChars += seg.validChars;
            }
            result.comparedChars = totalChars;
            AigcFeatures.DocStats stats = AigcFeatures.stats(norm, spans, scored);
            // 系数表只负责给证据起名字（模板句式/连接词那几类的中文名），不参与打分：见类注释。
            result.coefficients = AigcScorer.current(AigcFamily.CHINESE);
            result.coefficientVersion = result.coefficients.version();

            // 逐段读数：段分发给段内每一句，可疑判定与贡献判据也在这里一起算好。
            double[] rhythm = new double[scored.size()];
            boolean[] paragraphFlagged = new boolean[scored.size()];
            ArrayList<String[]> extras = new ArrayList<String[]>();
            for (int i = 0; i < scored.size(); i++) extras.add(new String[0]);
            applyRhythm(text, scored, paragraphs, excluded, rhythm, paragraphFlagged, extras, result);

            double weighted = 0d;
            AigcTells table = AigcTells.current();
            for (int i = 0; i < scored.size(); i++) {
                AigcFeatures.Segment seg = scored.get(i);
                ArrayList<AigcFeatures.Hit> hits = AigcFeatures.of(seg, stats);
                // 离线模型打分路径：装载了权重**且**清单里那两个实测数真过出厂门槛才走（见 AigcNgramModel）。
                // 拉丁族不许用中文模型打分，照旧走节奏判据。随包的 ngram-lr-2026-10-09 未达标，今天进不来。
                AigcNgramModel model = AigcNgramModel.engaged() && seg.family != AigcFamily.LATIN
                        ? AigcNgramModel.current() : null;
                double score;
                if (model != null) {
                    score = model.sentenceScore(seg.compact);
                    result.modelSentences++;
                    if (model.windowHit(seg.compact)) result.windowHits++;
                } else {
                    score = rhythm[i];
                }
                Sentence sentence = new Sentence();
                sentence.start = seg.start;
                sentence.end = seg.end;
                sentence.score = (float) score;
                for (int k = 0; k < hits.size(); k++) sentence.features.addAll(hits.get(k).evidence);
                for (int k = 0; k < extras.get(i).length; k++) addOnce(sentence.features, extras.get(i)[k]);
                if (table != null) {
                    ArrayList<AigcTells.Hit> found = table.sentenceHits(norm, seg.start, seg.end);
                    for (int k = 0; k < found.size(); k++) {
                        result.tells.add(found.get(k));
                        result.tellHits++;
                        addOnce(sentence.features, "套话线索：" + found.get(k).name);
                    }
                }
                result.sentences.add(sentence);
                if (seg.family == AigcFamily.LATIN) result.latinChars += seg.validChars;
                weighted += score * seg.validChars;
            }
            // 加权口径一个字没改；变的是分本身：段分是相对刻度，可正可负，不夹到 0~100。
            result.rate = (float) (totalChars == 0 ? 0d : weighted / totalChars);
            aggregate(result, scored, paragraphs, excluded, paragraphFlagged);
            // 段级的词表证据（scope=para 的那些条）：只在真装了表、真列出了证据时才带上那一句提醒。
            if (table != null) {
                paragraphTells(norm, scored, paragraphs, excluded, table, result);
                if (!result.tells.isEmpty()) result.evidenceCaveat = AigcTells.CAVEAT;
            }
            int triggers = result.rhythmTriggers + result.tellHits;
            result.densityPer1000 = totalChars <= 0 ? Double.NaN : triggers * 1000d / totalChars;
        }
        // 0 个计分句也算样本不足：宁可不给数，也不给一个看起来像结论的 0.00。
        result.insufficientSample = result.comparedChars < MIN_DOCUMENT_CHARS;
        result.tier = result.insufficientSample ? Tier.INSUFFICIENT_SAMPLE : tierOf(result);
        result.scaleP50 = AigcRhythm.humanP50();
        result.scaleP90 = AigcRhythm.humanP90();
        result.scaleP99 = AigcRhythm.humanP99();
        result.falsePositivesPerMille = AigcRhythm.falsePositivePerMille();
        result.confidence = confidenceOf(result);
        result.instrument = instrumentName(result);
        result.provenance = provenance(result);
        result.verdict = verdict(result);
        return result;
    }

    /** 词表类命中不重复列。 */
    private static void addOnce(ArrayList<String> into, String value) {
        if (value != null && value.length() > 0 && !into.contains(value)) into.add(value);
    }

    /**
     * 按自然段算节奏读数。分组规则与 {@link #aggregate} 用同一套：同一段、中间没被排除区间穿过才算一段。
     * 文本传的是**原文**（不是 normalize 过的）：判据的分句口径认的是中文句号，归一化会把它折成半角点，
     * 一折就和定标侧 {@code tools/aigc-rhythm.py} 对不上了。
     */
    private static void applyRhythm(String raw, ArrayList<AigcFeatures.Segment> scored,
                                    ArrayList<Integer> paragraphs, int[] excluded, double[] scores,
                                    boolean[] flagged, ArrayList<String[]> extras, Result result) {
        int i = 0;
        while (i < scored.size()) {
            int j = i;
            while (j + 1 < scored.size()
                    && paragraphs.get(j).intValue() == paragraphs.get(j + 1).intValue()
                    && !crosses(excluded, scored.get(j).end, scored.get(j + 1).start)) j++;
            AigcRhythm.Reading reading = AigcRhythm.measure(raw, scored.get(i).start, scored.get(j).end);
            result.paragraphsTotal++;
            if (reading.measured) {
                result.rhythmTriggers += reading.triggers;
                if (reading.spoke) {
                    result.rhythmParagraphs++;
                    result.rhythmSentences += reading.sentences;
                }
                if (reading.validChars >= AigcRhythm.flagChars()
                        && reading.sentences >= AigcRhythm.flagSentences()) result.judgeableParagraphs++;
            }
            if (reading.suspicious) {
                Paragraph paragraph = new Paragraph();
                paragraph.start = reading.start;
                paragraph.end = reading.end;
                paragraph.score = reading.score;
                paragraph.sentences = reading.sentences;
                paragraph.chars = reading.validChars;
                paragraph.criteria.addAll(reading.top);
                result.suspiciousParagraphs.add(paragraph);
            }
            String[] names = new String[reading.top.size()];
            // 贡献最大的那几条：19 条里任何一条都可能在里面（标点熵、字组类型比都不是"节奏"），前缀别再写死族名。
            for (int k = 0; k < names.length; k++) names[k] = "判据：" + reading.top.get(k);
            for (int k = i; k <= j; k++) {
                scores[k] = reading.score;
                flagged[k] = reading.suspicious;
                extras.set(k, names);
            }
            i = j + 1;
        }
    }

    /** 段级词表判据：同一段只跑一次，命中区间落在整段上。 */
    private static void paragraphTells(String norm, ArrayList<AigcFeatures.Segment> scored,
                                       ArrayList<Integer> paragraphs, int[] excluded,
                                       AigcTells table, Result result) {
        int i = 0;
        while (i < scored.size()) {
            int j = i;
            while (j + 1 < scored.size()
                    && paragraphs.get(j).intValue() == paragraphs.get(j + 1).intValue()
                    && !crosses(excluded, scored.get(j).end, scored.get(j + 1).start)) j++;
            int from = scored.get(i).start, to = scored.get(j).end;
            ArrayList<AigcTells.Hit> found = table.paragraphHits(norm, from, to);
            for (int k = 0; k < found.size(); k++) {
                result.tells.add(found.get(k));
                result.tellHits++;
                for (int m = i; m <= j; m++)
                    addOnce(result.sentences.get(m).features, "套话线索：" + found.get(k).name);
            }
            i = j + 1;
        }
    }

    /**
     * 分区（五条规则，逐条有断言）：① 每个计分句恰好落进一个区间，低分句也自成区间；② 相邻两句都
     * ≥ SEGMENT_SCORE_FLOOR、同自然段、同族、中间没有被排除区间穿过，且并完不超过句数与字数上限才并段；
     * ③ 区间分是成员句分的字符加权平均，不含加成；④ 排除区间把区间切断；⑤ 按 start 升序、互不重叠。
     * flagged 不再看区间分和 0.45 那条线，看的是**整段有没有过真人 P99**：节奏是段级性质，
     * 拿单句分去判可疑会把真人的一句长句判成机器。
     */
    private static void aggregate(Result result, ArrayList<AigcFeatures.Segment> scored,
                                  ArrayList<Integer> paragraphs, int[] excluded, boolean[] flagged) {
        int cursor = 0;
        while (cursor < scored.size()) {
            int from = cursor;
            int to = cursor;
            int chars = scored.get(from).validChars;
            while (to + 1 < scored.size()
                    && result.sentences.get(to + 1).score >= SEGMENT_SCORE_FLOOR
                    && result.sentences.get(to).score >= SEGMENT_SCORE_FLOOR
                    && (to - from + 1) < MAX_SEGMENT_SENTENCES
                    && paragraphs.get(to).intValue() == paragraphs.get(to + 1).intValue()
                    && scored.get(to).family == scored.get(to + 1).family
                    && !crosses(excluded, scored.get(to).end, scored.get(to + 1).start)
                    && chars + scored.get(to + 1).validChars <= MAX_SEGMENT_CHARS) {
                to++;
                chars += scored.get(to).validChars;
            }
            cursor = to + 1;
            AigcFeatures.Segment head = scored.get(from);
            Segment segment = new Segment();
            segment.fromSentence = from;
            segment.toSentence = to;
            segment.start = result.sentences.get(from).start;
            segment.end = result.sentences.get(to).end;
            segment.family = head.family;
            segment.chars = chars;
            double weighted = 0d;
            for (int i = from; i <= to; i++) {
                Sentence member = result.sentences.get(i);
                weighted += (double) member.score * scored.get(i).validChars;
                for (int k = 0; k < member.features.size(); k++) {
                    String evidence = member.features.get(k);
                    if (!segment.evidence.contains(evidence)) segment.evidence.add(evidence);
                }
            }
            segment.score = chars <= 0 ? 0f : (float) (weighted / chars);
            segment.flagged = flagged[from] && segment.score >= SEGMENT_FLAG_GATE;
            result.segments.add(segment);
        }
        // 可疑字数与档位的连续度按**过线的自然段**计，不按区间计：区间是展示用的切法（一句一断、
        // 每六句一切），拿它当"疑似机器生成多少字"会把同一段切成几截，也会让最长连续度取决于
        // 切法而不是取决于正文。区间上的 flagged 位留着，那是证据视图（CharLedger 数机器腔字数用它）。
        for (int i = 0; i < result.suspiciousParagraphs.size(); i++) {
            Paragraph paragraph = result.suspiciousParagraphs.get(i);
            result.flaggedSegments++;
            result.flaggedChars += paragraph.chars;
            if (paragraph.sentences > result.longestRunSentences) {
                result.longestRunSentences = paragraph.sentences;
                result.longestRunChars = paragraph.chars;
            }
        }
    }

    /** 两个计分句之间是否横着一段被排除的文本（引用、参考文献表、结构性文本）。 */
    private static boolean crosses(int[] excluded, int from, int to) {
        for (int i = 0; i + 1 < excluded.length; i += 2)
            if (excluded[i] < to && excluded[i + 1] > from) return true;
        return false;
    }

    /** 自然段序号：换行符计一段。区间不跨段，因为 1.0.0 要点证据跳正文，跨段区间会跨页，UI 解释不清。 */
    private static int[] lineBreakOffsets(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') count++;
        int[] out = new int[count];
        int at = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') out[at++] = i;
        return out;
    }

    /**
     * 置信档位：可疑字数（绝对量）与最长可疑区间的句数（连续度）双门槛，再配两条真人刻度线。
     * 那两条线不再是写死的 45/20/10 分——尺度换了，现在直接跟真人段落分的 P90 / P99 比。
     * 表没装载时这两个数是 NaN，任何比较都不成立，档位自然退到"零散痕迹"以下，不会假称没问题。
     */
    static Tier tierOf(Result result) {
        if (result.flaggedChars >= TIER_STRONG_CHARS && result.longestRunSentences >= TIER_STRONG_RUN
                && result.rate >= AigcRhythm.humanP99()) return Tier.STRONG;
        if (result.flaggedChars >= TIER_REVIEW_CHARS && result.longestRunSentences >= TIER_REVIEW_RUN
                && result.rate >= AigcRhythm.humanP90()) return Tier.NEEDS_REVIEW;
        if (result.flaggedChars > 0 || result.rate >= AigcRhythm.humanP90()) return Tier.WATCH;
        return Tier.NONE;
    }

    /**
     * 这个指数成不成立。三种情况不成立，每一种都必须用一句话说出来，不许留空白：
     * ① 有效字符不够 {@link #MIN_DOCUMENT_CHARS}；② 判据表没装载；③ 一段都凑不满
     * {@link AigcRhythm#MIN_SENTENCES} 句，节奏分布算不出来。那时候的 0 是"没量到"，不是"没有机器腔"。
     */
    public static boolean indexable(Result result) {
        if (result == null || result.insufficientSample || !AigcRhythm.engaged()) return false;
        if (result.rhythmSentences <= 0) return false;
        return !latinOnly(result);
    }

    /**
     * 计分句里拉丁族过半。判据表是在中文论文正文上定标的（{@code docs/aigc-rhythm.md} 第 1 节），
     * 分句口径也不认英文句号，一份纯英文稿子在这件仪器上算出来的分不在定标范围内。
     * 那不是"分数低"，是"这个数不成立"，必须说出来而不是印出去。
     */
    static boolean latinOnly(Result result) {
        return result != null && result.comparedChars > 0
                && result.latinChars * 2 > result.comparedChars;
    }

    /** 疑似机器生成字数占参与判定正文的百分比（分母是 {@link Result#comparedChars}，不是全文字数）。 */
    public static double share(Result result) {
        if (result == null || result.comparedChars <= 0) return 0d;
        double value = result.flaggedChars * 100d / result.comparedChars;
        return value > 100d ? 100d : value < 0d ? 0d : value;
    }

    /** 指数的写法：一位小数，不带百分号。它是一条相对刻度的分，不是占比，可以小于 0。 */
    public static String indexText(double value) {
        double safe = Double.isNaN(value) || Double.isInfinite(value) ? 0d : value;
        return String.format(java.util.Locale.US, "%.1f", Double.valueOf(safe));
    }

    /** 两位小数：真人刻度与划线分数用这个写法，跟指数区分开。 */
    static String scaleText(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "无";
        return String.format(java.util.Locale.US, "%.2f", Double.valueOf(value));
    }

    /** ①的后半：把真人论文正文的三把刻度并排给出来，光一个指数没人知道是高是低。 */
    public static String scaleLine(Result result) {
        if (result == null || Double.isNaN(result.scaleP99))
            return "真人论文正文的刻度还没装载，暂时只能看绝对分";
        return "真人论文正文：中位 " + scaleText(result.scaleP50)
                + " · 九成段落低于 " + scaleText(result.scaleP90)
                + " · 九成九段落低于 " + scaleText(result.scaleP99);
    }

    /** ②：可疑字数与占比，并带上划线口径和那一线的实测误报率。一个都不许多承诺。 */
    public static String flaggedLine(Result result) {
        if (result == null) return NOT_FOUND;
        if (result.flaggedChars <= 0) {
            /* 一个数都没有过线，有两种完全不同的原因：真没东西过线，和压根没有一段够判定规模。
               后一种写成"未发现"会让人以为查过了，所以这里必须把"判不了"与"没发现"分开说。 */
            if (result.judgeableParagraphs == 0)
                return "没有一段达到判定规模（整段要不少于 " + AigcRhythm.flagChars() + " 字、不少于 "
                        + AigcRhythm.flagSentences() + " 句），这一轮判不了可疑字数——判不了不等于没有机器生成";
            return NOT_FOUND + "，这不等于没有机器生成（长句连写能躲过，见出处那一条）";
        }
        return "疑似机器生成 " + result.flaggedChars + " 字，占参与判定的正文 "
                + result.comparedChars + " 字的 " + shareText(share(result)) + "%"
                + "；划线口径：整段分数高过真人论文正文的九成九分位 " + scaleText(result.scaleP99)
                + "，这一线实测真人误报 " + falsePositiveText(result);
    }

    /** 划线那一档的真人误报率，写法是百分数。 */
    public static String falsePositiveText(Result result) {
        double perMille = result == null ? AigcRhythm.MEASURED_FALSE_POSITIVES_PER_MILLE
                : result.falsePositivesPerMille;
        return String.format(java.util.Locale.US, "%.2f%%", Double.valueOf(perMille / 10d));
    }

    /** ③的一行：段级分数 + 规模 + 贡献最大的几条判据。列表点回正文，这行就是它的内容。 */
    public static String paragraphText(Paragraph paragraph) {
        if (paragraph == null) return "";
        StringBuilder out = new StringBuilder("指数 ").append(indexText(paragraph.score))
                .append(" · ").append(paragraph.chars).append(" 字 · ")
                .append(paragraph.sentences).append(" 句");
        if (!paragraph.criteria.isEmpty()) {
            out.append(" · 主要判据：");
            for (int i = 0; i < paragraph.criteria.size(); i++) {
                if (i > 0) out.append("、");
                out.append(paragraph.criteria.get(i));
            }
        }
        return out.toString();
    }

    /** 占比的写法：一位小数。 */
    public static String shareText(double value) {
        double safe = Double.isNaN(value) || Double.isInfinite(value) ? 0d : value;
        if (safe < 0d) safe = 0d;
        if (safe > 100d) safe = 100d;
        return String.format(java.util.Locale.US, "%.1f", Double.valueOf(safe));
    }

    /** 一句人话结论：档位 + 指数与刻度 + 可疑字数与占比 + 出处。指数不成立时只说为什么不成立。 */
    static String verdict(Result result) {
        StringBuilder out = new StringBuilder(measurement(result));
        if (!indexable(result)) return out.toString();
        out.append("；AI 倾向指数 ").append(indexText(result.rate)).append(" 分，")
                .append(scaleLine(result)).append("；").append(result.confidence.use());
        out.append("；").append(flaggedLine(result));
        // 出处那一整段（版本号、AUC、误报、置信档、软肋）不夹进结论句里：报告中心与界面把它单独
        // 排在最后一行，夹进来就是同一件事说两遍，两处哪天写法不一致就再也对不上了。
        // 结论句只带末尾那一句"这是复核线索，不是判决"。
        out.append("。").append(NOT_A_VERDICT);
        return out.toString();
    }

    /**
     * 五个档位（或一条不成立的理由）的人话。样本不足、表没装载、节奏分布算不出来这三条排在档位之前：
     * 那三种场合下后面那几句都会被读成"查过了，没问题"，而那正是错的。
     */
    static String measurement(Result result) {
        if (result.insufficientSample)
            return "样本不足（有效字符 " + result.comparedChars + " 字，门槛 " + MIN_DOCUMENT_CHARS
                    + " 字），只列触发的判据，不给倾向指数";
        if (!AigcRhythm.engaged()) return AigcRhythm.NOT_LOADED;
        if (latinOnly(result))
            return "计分正文以英文为主，判据表是在中文论文正文上定标的，这一份不在它的定标范围内，"
                    + "量不出倾向指数（这不等于没有机器腔）";
        if (result.rhythmSentences <= 0)
            return "段落普遍不足 " + AigcRhythm.MIN_SENTENCES
                    + " 句，节奏分布算不出来，量不出倾向指数（这不等于没有机器腔）";
        switch (result.tier) {
            case STRONG:
                return "成段机器腔（" + result.flaggedSegments + " 段 / " + result.flaggedChars
                        + " 字），建议整节复核";
            case NEEDS_REVIEW:
                return "部分段落有机器腔，建议逐段复核";
            case WATCH:
                return "零散痕迹，规模与连续度都不足以下判断";
            default:
                return NOT_FOUND;
        }
    }

    /**
     * 置信档只认**当前真正参与打分那件仪器**的实测读数：节奏判据带着自己的 AUC 与误报率；
     * 离线模型真达标并参与打分时一起算，取最弱那一件。{@link AigcScorer} 那套启发式今天不参与打分，
     * 所以它的 calibrated() 既不挡这条路，也不进这一档。
     */
    static AigcConfidence confidenceOf(Result result) {
        AigcConfidence out = AigcRhythm.confidence();
        if (result != null && result.modelSentences > 0 && AigcNgramModel.current() != null) {
            AigcNgramModel.Manifest manifest = AigcNgramModel.current().manifest();
            out = AigcConfidence.weakest(out, AigcConfidence.of(manifest.holdoutAuc,
                    manifest.holdoutHumanFpPerMille, AigcNgramModel.engaged()));
        }
        return out;
    }

    /** 本次给分的那件仪器。名字里带版本号，报告里的每个数都要查得出是谁算的。 */
    static String instrumentName(Result result) {
        StringBuilder out = new StringBuilder("仪器 本机判据表（")
                .append(AigcRhythm.RHYTHM).append(" 条节奏结构 + ")
                .append(AigcRhythm.CRITERIA - AigcRhythm.RHYTHM).append(" 条用词统计");
        if (result != null && result.modelSentences > 0 && AigcNgramModel.current() != null)
            out.append(" + 离线模型 ").append(AigcNgramModel.current().manifest().version);
        if (AigcTells.current() != null) out.append("；套话词表只列证据不计分");
        return out.toString();
    }

    /**
     * 那一行出处：仪器 + 实测精度 + 划线误报 + 还没验过什么 + 置信档 + 已知软肋。
     * 软肋那两条（长句连写能躲、整齐等长排版会误报）必须跟着数一起出现，不许只留精度不留限制。
     */
    static String provenance(Result result) {
        StringBuilder out = new StringBuilder();
        join(out, instrumentName(result));
        join(out, AigcRhythm.describe());
        if (result != null) {
            if (result.modelSentences > 0) join(out, AigcNgramModel.describe());
            join(out, "置信档 " + result.confidence.label() + "（" + result.confidence.use() + "）");
            if (result.paragraphsTotal > 0 && result.rhythmParagraphs < result.paragraphsTotal)
                join(out, result.rhythmParagraphs + "/" + result.paragraphsTotal
                        + " 段算得出节奏分布，其余段落句数太少");
            if (!Double.isNaN(result.densityPer1000))
                join(out, AigcBaseline.densityLine(result.densityPer1000, AigcBaseline.AGGREGATE));
        }
        join(out, AigcRhythm.caveatLine());
        join(out, AigcRhythm.WEAK_LAYOUT);
        join(out, AigcRhythm.OUT_OF_DOMAIN);
        join(out, AigcRhythm.WEAK_UNDETECTED);
        return trimTail(out).append("。").append(NOT_A_VERDICT).toString();
    }

    /** 出处行按"；"分段拼，每段自己带的句末标点先抹掉，免得出现"。；"这种连写。 */
    private static void join(StringBuilder into, String part) {
        if (part == null || part.trim().length() == 0) return;
        if (into.length() > 0) into.append('；');
        into.append(part.trim());
        trimTail(into);
    }

    private static StringBuilder trimTail(StringBuilder out) {
        while (out.length() > 0 && (out.charAt(out.length() - 1) == '。'
                || out.charAt(out.length() - 1) == '；' || out.charAt(out.length() - 1) == '，'))
            out.setLength(out.length() - 1);
        return out;
    }
}

package com.rikkahub.wordlite;

import java.util.ArrayList;

/**
 * 本机 AIGC 倾向检测的编排层：切句 → 取特征值（{@link AigcFeatures}）→ 打分（{@link AigcScorer}）→
 * 相邻同腔句并成区间 → 双门槛定档。这一层一个权重都不写，也不再自己量文本。
 *
 * 两条口径是写死了不许动的：
 * 1. {@link Result#rate} 仍然是**字符加权的句分均值**（Σ 句分 × 有效字符 / Σ 有效字符），定义一个字没改。
 *    区间是"分区不挑选"的视图——每个计分句恰好落进一个区间，低分句也自成区间——于是
 *    Σ 区间分 × 字数 / Σ 字数 == rate 是恒等式，聚合只改视图，一个字符都没被重复计或漏掉。
 * 2. 有效字符不足 {@link #MIN_DOCUMENT_CHARS} 只给"样本不足"，不给比例。0 个计分句同样算样本不足
 *    （0.5.4 在这里留着 insufficientSample=false，报告那一格会印出 0.00%，与
 *    docs/oss-algorithms.md"可比对字数不足时禁止输出 0.00%"直接冲突，本版改掉）。
 *
 * 加成只进档位判定（{@link Tier}），不进任何百分比：rate、flaggedChars、Segment.score 三者口径互不打架。
 */
public final class AigcDetector {
    /** 有效字符数少于该值的句子不计分。 */
    public static final int MIN_SENTENCE_CHARS = 8;
    /** 低于这个有效字符数只给特征提示，不给百分比。 */
    public static final int MIN_DOCUMENT_CHARS = 400;
    /**
     * 相邻两句都要到这条线才并段。0.35 来自实测：真人论文里 0.45 档的高分句 100% 是孤立的
     * （≥2 连句的字符占比 0.0%），而机写骨架稿的分数是成片出现的（同档 4.5%、5/70 段成片）。
     * 孤立的高分句在真人论文里是常态，成片的同腔成段几乎不存在——区间聚合买的就是这个差别。
     */
    public static final float SEGMENT_SCORE_FLOOR = 0.35f;
    /** 区间分过这条线才算可疑区间，{@code flaggedChars} 由它来。取值见 docs/aigc-calibration.md 的 TIER 行。 */
    public static final float SEGMENT_FLAG_GATE = 0.45f;
    /** 一个区间最多并几句、最多多少有效字符：WCopyfind"按绝对量而不是只看比例"的同一条纪律。 */
    public static final int MAX_SEGMENT_SENTENCES = 6;
    public static final int MAX_SEGMENT_CHARS = 1200;
    /** 档位的双门槛：可疑字数是绝对量，最长可疑区间的句数是连续度，两个一起看。 */
    public static final int TIER_STRONG_CHARS = 400, TIER_REVIEW_CHARS = 200;
    public static final int TIER_STRONG_RUN = 3, TIER_REVIEW_RUN = 2;
    public static final float TIER_STRONG_RATE = 45f, TIER_REVIEW_RATE = 20f, TIER_WATCH_RATE = 10f;

    /** 置信档位。顺序即强弱，报告与断言按 ordinal 比高低。 */
    public enum Tier {
        /** 可比对字数不够，只列特征。 */
        INSUFFICIENT_SAMPLE,
        /** 未见明显机器腔。 */
        NONE,
        /** 有痕迹但比例或连续度不足以下判断。 */
        WATCH,
        /** 成片的机器腔，值得复核。 */
        NEEDS_REVIEW,
        /** 成片且量大，整节复核。 */
        STRONG
    }

    public static final class Sentence {
        public int start, end;                   // 原串偏移
        public float score;                      // 0..1
        public final ArrayList<String> features = new ArrayList<String>(); // 中文特征名
    }

    /** 分区视图：成员句的并集，不扩边；每个计分句恰好属于一个区间。 */
    public static final class Segment {
        public int start, end;                   // 成员句的并集偏移（不扩边）
        public int fromSentence, toSentence;     // 在 Result.sentences 里的下标闭区间
        public int chars;                        // 成员句有效字符之和（不是 UTF-16 跨度）
        public float score;                      // 成员句分的字符加权平均，不含任何加成
        public int family;                       // AigcFamily.CHINESE / LATIN / MIXED
        public boolean flagged;                  // 区间分 ≥ SEGMENT_FLAG_GATE
        /** 与查重命中重叠的字数，由查重侧回填（0.7.2 口径互扣，见 artifacts/research/aigc-handoff.md）。 */
        public int duplicatedChars;
        public final ArrayList<String> evidence = new ArrayList<String>(); // 成员句依据去重合并
    }

    public static final class Result {
        public final ArrayList<Sentence> sentences = new ArrayList<Sentence>();
        /** 计分句的分区视图：按原文顺序、互不重叠、无缝覆盖全部计分句。 */
        public final ArrayList<Segment> segments = new ArrayList<Segment>();
        public float rate;                       // 定义不变：Σ 句分×有效字符 / Σ 有效字符
        public int comparedChars;
        /** 引用区间与参考文献表里没参与打分的字数。 */
        public int excludedChars;
        /** 可疑区间的有效字符之和（不是 UTF-16 跨度，代理对不再算两个）。 */
        public int flaggedChars;
        /** 可疑区间的个数与其中最长的句数/字数，档位判定的依据，不是黑箱。 */
        public int flaggedSegments, longestRunSentences, longestRunChars;
        public Tier tier = Tier.INSUFFICIENT_SAMPLE;
        /** 有效字符不足 MIN_DOCUMENT_CHARS，比例不可信。 */
        public boolean insufficientSample;
        /** 一句人话结论，替代拿百分比当判决。 */
        public String verdict = "";
        /** 本次真正用的系数表（族取中文族；拉丁族的表由它平移而来）。 */
        public AigcScorer.Coefficients coefficients;
        public String coefficientVersion = "";
    }

    private AigcDetector() { }

    /** 逐句给出机器生成倾向分，整篇比例按字符加权；短于 MIN_SENTENCE_CHARS 的句子不参与。 */
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
            result.coefficients = AigcScorer.current(AigcFamily.CHINESE);
            result.coefficientVersion = result.coefficients.version();
            double weighted = 0d;
            for (int i = 0; i < scored.size(); i++) {
                AigcFeatures.Segment seg = scored.get(i);
                ArrayList<AigcFeatures.Hit> hits = AigcFeatures.of(seg, stats);
                double score = AigcScorer.score(hits, AigcScorer.current(seg.family));
                Sentence sentence = new Sentence();
                sentence.start = seg.start;
                sentence.end = seg.end;
                sentence.score = (float) score;
                for (int k = 0; k < hits.size(); k++) sentence.features.addAll(hits.get(k).evidence);
                result.sentences.add(sentence);
                weighted += score * seg.validChars;
            }
            double rate = totalChars == 0 ? 0d : weighted * 100d / totalChars;
            result.rate = (float) (rate > 100d ? 100d : rate);
            aggregate(result, scored, paragraphs, excluded);
        }
        // 0 个计分句也算样本不足：宁可不给数，也不给一个看起来像结论的 0.00%。
        result.insufficientSample = result.comparedChars < MIN_DOCUMENT_CHARS;
        result.tier = result.insufficientSample ? Tier.INSUFFICIENT_SAMPLE : tierOf(result);
        result.verdict = verdict(result);
        return result;
    }

    /**
     * 分区（五条规则，逐条有断言）：① 每个计分句恰好落进一个区间，低分句也自成区间；② 相邻两句都
     * ≥ SEGMENT_SCORE_FLOOR、同自然段、同族、中间没有被排除区间穿过，且并完不超过句数与字数上限才并段；
     * ③ 区间分是成员句分的字符加权平均，不含加成；④ 排除区间把区间切断；⑤ 按 start 升序、互不重叠。
     */
    private static void aggregate(Result result, ArrayList<AigcFeatures.Segment> scored,
                                  ArrayList<Integer> paragraphs, int[] excluded) {
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
            segment.flagged = segment.score >= SEGMENT_FLAG_GATE;
            result.segments.add(segment);
            if (!segment.flagged) continue;
            result.flaggedSegments++;
            result.flaggedChars += segment.chars;
            if (to - from + 1 > result.longestRunSentences) {
                result.longestRunSentences = to - from + 1;
                result.longestRunChars = segment.chars;
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

    /** 置信档位：可疑字数（绝对量）与最长可疑区间的句数（连续度）双门槛，再配两条比例线。 */
    static Tier tierOf(Result result) {
        if (result.flaggedChars >= TIER_STRONG_CHARS && result.longestRunSentences >= TIER_STRONG_RUN
                && result.rate >= TIER_STRONG_RATE) return Tier.STRONG;
        if (result.flaggedChars >= TIER_REVIEW_CHARS && result.longestRunSentences >= TIER_REVIEW_RUN
                && result.rate >= TIER_REVIEW_RATE) return Tier.NEEDS_REVIEW;
        if (result.flaggedChars > 0 || result.rate >= TIER_WATCH_RATE) return Tier.WATCH;
        return Tier.NONE;
    }

    /** 样本不足时禁止给比例，其余只说倾向与档位，不做判决（MOSS"分数只用于相对比较"）。 */
    static String verdict(Result result) {
        // 方向没验正过就谈不上"这次量得准不准"：这一句排在样本量之前，先认错再谈字数。
        if (!AigcScorer.calibrated())
            return AigcScorer.UNCALIBRATED_NOTE + "，所以本次只列触发过的判据证据，不给生成比例";
        return measurement(result);
    }

    /**
     * 判据方向被带标注语料验正之后才轮到用户看的那几句话（样本不足那条 + 五个档位那条）。
     * 未标定期间只有回归读得到它：档位口径一条没改，tests/AigcRegression.tiers() 继续逐档盯着这段文案，
     * 挡在用户前面的只是"现在还不该说出口"这一层（{@link AigcScorer#calibrated()}）。
     */
    static String measurement(Result result) {
        if (result.insufficientSample)
            return "样本不足（有效字符 " + result.comparedChars + "，门槛 " + MIN_DOCUMENT_CHARS
                    + "），只列特征，不给生成比例";
        switch (result.tier) {
            case STRONG:
                return "成段机器腔（" + result.flaggedSegments + " 段 / " + result.flaggedChars
                        + " 字），建议整节复核";
            case NEEDS_REVIEW:
                return "部分段落有机器腔，建议复核高分句";
            case WATCH:
                return "零散句子有机器腔痕迹，比例与连续度都不足以下判断";
            default:
                return "未见明显机器腔";
        }
    }
}
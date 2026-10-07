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
 * 不变式：一条句段的 {@link Hit} 非空 ⇔ 至少一条特征取值 > 0 ⇔ 依据文案非空。句分本身还要看系数
 * （v1 表里没有零权重，所以句分 > 0 与这三者等价；将来若把某条权重归零，这条口径要一起改）。
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
        }
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
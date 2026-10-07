package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 本机 AIGC 倾向检测：句长突发度、连接词密度、模板句式、句首重复、字符二元组熵、标点画像与实词多样度。 */
public final class AigcDetector {
    /** 有效字符数少于该值的句子不计分。 */
    public static final int MIN_SENTENCE_CHARS = 8;
    private static final double TEMPLATE_WEIGHT = 0.30d;
    private static final double CONNECTIVE_WEIGHT = 0.18d;
    private static final double BURST_WEIGHT = 0.12d;
    private static final double OPENING_WEIGHT = 0.12d;
    private static final double PUNCT_WEIGHT = 0.11d;
    private static final double ENTROPY_WEIGHT = 0.08d;
    private static final double DIVERSITY_WEIGHT = 0.08d;
    private static final double PARALLEL_WEIGHT = 0.12d;
    private static final double INTENSIFIER_WEIGHT = 0.16d;
    private static final double FLAT_CV = 0.28d;
    private static final double OPENING_RATIO = 0.45d;
    private static final double LOW_ENTROPY = 3.4d;
    private static final double LOW_DIVERSITY = 0.78d;
    private static final double LOW_WORD_DIVERSITY = 0.62d;

    public static final class Sentence {
        public int start, end;                   // 原串偏移
        public float score;                      // 0..1
        public final ArrayList<String> features = new ArrayList<String>(); // 中文特征名
    }

    public static final class Result {
        public final ArrayList<Sentence> sentences = new ArrayList<Sentence>();
        public float rate;                       // 按字符加权的百分比 0..100
        public int comparedChars;
    }

    private static final class Template {
        final String name;
        final Pattern pattern;
        Template(String name, String regex) {
            this.name = name;
            this.pattern = Pattern.compile(regex);
        }
    }

    private static final Template[] TEMPLATES = {
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

    private static final String[] CONNECTIVES = {
        "首先", "其次", "再次", "然后", "最后", "第一", "第二", "第三", "因此", "所以", "因而", "从而", "而且", "并且",
        "此外", "另外", "同时", "不仅", "但是", "然而", "不过", "尽管", "虽然", "由于", "因为", "为了", "通过", "根据",
        "针对", "基于", "总之", "综上", "可见", "换言之", "也就是说", "总的来说", "总体而言", "需要", "应当", "必须", "能够",
        "可以",
    };

    private static final String[] EN_CONNECTIVES = {
        "moreover", "furthermore", "additionally", "in addition", "however", "therefore", "thus",
        "consequently", "nevertheless", "firstly", "secondly", "finally", "in conclusion",
        "on the one hand", "on the other hand", "in contrast", "for instance",
    };

    private static final String[] INTENSIFIERS = {
        "显著", "有效", "充分", "全面", "极大", "大幅", "明显", "有力", "重要", "关键", "核心", "高效",
        "稳定", "优异", "突出", "扎实", "深入", "广泛", "严格", "合理",
    };

    private AigcDetector() { }

    /** 逐句给出机器生成倾向分，整篇比例按字符加权；短于 MIN_SENTENCE_CHARS 的句子不参与。 */
    public static Result detect(String text) {
        Result result = new Result();
        if (text == null || text.length() == 0) return result;
        String norm = TextCorpus.normalize(text);
        ArrayList<int[]> spans = TextCorpus.sentences(text);
        ArrayList<int[]> scored = new ArrayList<int[]>();
        double[] all = new double[spans.size()];
        int totalChars = 0;
        for (int i = 0; i < spans.size(); i++) {
            int[] span = spans.get(i);
            int chars = TextCorpus.validCount(norm, span[0], span[1]);
            all[i] = chars;
            // 只有标点的短串不算一句话：实义字符不足 MIN_SENTENCE_CHARS 就不计分。
            if (chars < MIN_SENTENCE_CHARS || contentCount(norm, span[0], span[1]) < MIN_SENTENCE_CHARS) continue;
            scored.add(span);
            totalChars += chars;
        }
        if (scored.isEmpty()) return result;
        result.comparedChars = totalChars;
        double[] lengths = new double[scored.size()];
        HashMap<String, Integer> openings = new HashMap<String, Integer>();
        for (int i = 0; i < scored.size(); i++) {
            int[] span = scored.get(i);
            int chars = TextCorpus.validCount(norm, span[0], span[1]);
            lengths[i] = chars;
            String prefix = opening(norm, span[0], span[1]);
            if (prefix.length() > 0) count(openings, prefix);
        }
        double mean = 0d;
        for (double length : all) mean += length;
        mean /= all.length;
        double variance = 0d;
        for (double length : all) variance += (length - mean) * (length - mean);
        variance /= all.length;
        double cv = mean <= 0d ? 0d : Math.sqrt(variance) / mean;
        boolean flat = lengths.length >= 4 && cv < FLAT_CV;
        int dominantCount = 0;
        String dominant = "";
        for (Map.Entry<String, Integer> entry : openings.entrySet()) {
            if (entry.getValue().intValue() > dominantCount) {
                dominantCount = entry.getValue().intValue();
                dominant = entry.getKey();
            }
        }
        boolean repeatedOpening = scored.size() >= 5 && dominantCount >= OPENING_RATIO * scored.size();
        double weighted = 0d;
        for (int i = 0; i < scored.size(); i++) {
            int[] span = scored.get(i);
            int chars = (int) lengths[i];
            Sentence sentence = score(norm, span, chars, flat, cv, dominant, repeatedOpening);
            result.sentences.add(sentence);
            weighted += sentence.score * chars;
        }
        double rate = weighted * 100d / totalChars;
        result.rate = (float) (rate > 100d ? 100d : rate);
        return result;
    }

    private static Sentence score(String norm, int[] span, int chars, boolean flat, double cv,
                                  String dominant, boolean repeatedOpening) {
        Sentence sentence = new Sentence();
        sentence.start = span[0];
        sentence.end = span[1];
        String body = norm.substring(span[0], Math.min(norm.length(), span[1]));
        double total = 0d;
        int templates = 0;
        for (Template template : TEMPLATES) {
            if (!template.pattern.matcher(body).find()) continue;
            if (templates < 2) total += TEMPLATE_WEIGHT;
            else if (templates < 4) total += TEMPLATE_WEIGHT * 0.35d;
            if (templates < 4) sentence.features.add("模板句式：" + template.name);
            templates++;
        }
        int connectives = countOccurrences(body, CONNECTIVES) + countOccurrences(body, EN_CONNECTIVES);
        if (connectives >= 2) {
            total += CONNECTIVE_WEIGHT;
            sentence.features.add("连接词密度偏高");
        }
        if (flat) {
            total += BURST_WEIGHT;
            sentence.features.add("句长突发度低（变异系数 " + format(cv) + "）");
        }
        if (repeatedOpening) {
            total += OPENING_WEIGHT;
            sentence.features.add("句首结构重复（常以「" + dominant + "」开头）");
        }
        String compact = TextCorpus.compactOf(body);
        if (compact.length() == 0) {
            sentence.score = 0f;
            return sentence;
        }
        int semicolons = 0;
        int dashes = 0;
        int quotes = 0;
        int commas = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == ';') semicolons++;
            else if (c == '-') dashes++;
            else if (c == '"') quotes += 1;
            else if (c == ',') commas++;
        }
        if (semicolons >= 2) {
            total += PUNCT_WEIGHT;
            sentence.features.add("分号密集（并列长句）");
        } else if (semicolons >= 1) {
            total += PUNCT_WEIGHT * 0.6d;
            sentence.features.add("分号衔接并列分句");
        } else if (dashes >= 1) {
            total += PUNCT_WEIGHT * 0.8d;
            sentence.features.add("破折号使用");
        }
        if (quotes >= 4) {
            total += PUNCT_WEIGHT * 0.6d;
            sentence.features.add("引号密度偏高");
        }
        if ((semicolons >= 1 && commas >= 3) || (commas >= 4 && body.length() < 90)) {
            total += PARALLEL_WEIGHT;
            sentence.features.add("并列排比密集");
        }
        int intensifiers = countOccurrences(body, INTENSIFIERS);
        if (intensifiers >= 2) {
            total += INTENSIFIER_WEIGHT;
            sentence.features.add("程度副词/评价词堆叠");
        }
        double entropy = bigramEntropy(compact);
        if (chars >= 24 && entropy <= LOW_ENTROPY) {
            total += ENTROPY_WEIGHT;
            sentence.features.add("字符二元组熵偏低（" + format(entropy) + " bit，局部重复表达）");
        }
        double diversity = diversity(compact);
        double floor = isLatin(compact) ? LOW_WORD_DIVERSITY : LOW_DIVERSITY;
        if (chars >= 24 && diversity <= floor) {
            total += DIVERSITY_WEIGHT;
            sentence.features.add("实词多样度低（用词重复，" + format(diversity) + "）");
        }
        sentence.score = (float) (total > 1d ? 1d : total);
        return sentence;
    }

    /** 实义字符数：汉字、假名、拉丁字母与数字，标点符号不计。 */
    static int contentCount(String norm, int from, int to) {
        int count = 0;
        for (int i = from; i < to; i++) {
            char c = norm.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) count++;
            else if (c >= 0x4E00 && c <= 0x9FFF) count++;
            else if (c >= 0x3040 && c <= 0x30FF) count++;
        }
        return count;
    }

    private static void count(HashMap<String, Integer> map, String key) {
        Integer previous = map.get(key);
        map.put(key, Integer.valueOf(previous == null ? 1 : previous.intValue() + 1));
    }

    private static int countOccurrences(String body, String[] terms) {
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
    private static String opening(String norm, int from, int to) {
        int i = from;
        while (i < to && !isSignatureChar(norm.charAt(i))) i++;
        if (i >= to) return "";
        char first = norm.charAt(i);
        StringBuilder out = new StringBuilder();
        if ((first >= 'a' && first <= 'z') || (first >= '0' && first <= '9')) {
            while (i < to && ((norm.charAt(i) >= 'a' && norm.charAt(i) <= 'z')
                    || (norm.charAt(i) >= '0' && norm.charAt(i) <= '9'))) {
                if (out.length() < 12) out.append(norm.charAt(i));
                i++;
            }
            return out.toString();
        }
        while (i < to && out.length() < 2) {
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
    private static double bigramEntropy(String compact) {
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

    /** 实词多样度：拉丁文按词形去重，中文按字去重。 */
    private static boolean isLatin(String compact) {
        return latinRatio(compact) >= 0.5d;
    }

    private static double latinRatio(String compact) {
        int latin = 0;
        for (int i = 0; i < compact.length(); i++) {
            if (compact.charAt(i) >= 'a' && compact.charAt(i) <= 'z') latin++;
        }
        return compact.length() == 0 ? 0d : (double) latin / compact.length();
    }

    private static double diversity(String compact) {
        int total = 0;
        if (latinRatio(compact) >= 0.5d) {
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

    private static String format(double value) {
        return String.format(java.util.Locale.US, "%.2f", Double.valueOf(value));
    }

    private static double log2(double value) {
        return value <= 0d ? 0d : Math.log(value) / LN2;
    }
}

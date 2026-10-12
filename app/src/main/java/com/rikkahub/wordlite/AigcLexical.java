package com.rikkahub.wordlite;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * 词汇统计族四条判据（L02~L05）：与"句子怎么切"无关的一族，专门补"把相邻句合并就能躲"这个软肋。
 *
 * <p><b>口径与 {@code tools/aigc-lexical.py} 第 29~79 行逐位对齐</b>，两边算不出两个数：
 * <ul>
 *   <li>归一化 {@code norm(t)}：去掉所有 Unicode 空白，其余字符一个都不动（Python
 *       {@code re.sub(r"\s+","",t)}）；</li>
 *   <li>字组 = 归一化之后<b>按码点</b>滑动的两字符窗口，共 {@code n-1} 个；</li>
 *   <li>L02 字组类型比 = 不同二元组数 / 二元组总数（分母是总次数）；</li>
 *   <li>L03 只出现一次的字组占比 = 只出现一次的那类二元组<b>个数</b> / 二元组总次数；</li>
 *   <li>L04 字符熵 = 单字频率分布的香农熵，自然对数；</li>
 *   <li>L05 标点熵 = 对"既不是字母数字、也不在 {@code \u4e00-\u9fff} 之内"的字符做频率分布熵，自然对数。</li>
 * </ul>
 * 空串与单字符串一律给 0，不许抛异常；没有二元组、没有频率总量时同样给 0。
 *
 * <p><b>为什么必须按码点迭代。</b>黄金样本里"边界-补充平面"那一段含 U+1F600，Python 的字符串切片
 * 按下标取的是码点，Java 若按 UTF-16 迭代，字组就会在多字节字符上被劈成两半，两边当场对不上。
 *
 * <p><b>{@code isalnum()} 这一处最容易被抄错。</b>Python 的 {@code str.isalnum()} 把日文假名、
 * 全角数字、上标数字（¹ ²）、罗马数字（Ⅲ）、带圈数字（①）全算成字母数字，Java 的
 * {@link Character#isLetterOrDigit(int)} 只认 {@code L*} 与 {@code Nd}，差的那一截正是
 * {@code Nl}（字母数字）与 {@code No}（其他数字）。差一个字符，标点熵的分布就换了一张表，
 * 所以这里把两类数字一起算进来，由 {@code tests/AigcInstrumentParityRegression} 拿黄金样本兜底。
 *
 * <p>四条判据的权重、方向与真人基线都在随包表 {@link AigcRhythm#FILE} 里，这里只算值。
 */
public final class AigcLexical {

    /** 字组长度：字符二元组，与 Python {@code grams(t, n=2)} 一样。 */
    public static final int GRAM = 2;

    private AigcLexical() { }

    /** L02 字组类型比 = 不同二元组数 / 二元组总数；一个二元组都没有时给 0。 */
    public static double typeRatio(String text) {
        String[] grams = grams(norm(text));
        if (grams.length == 0) return 0d;
        HashSet<String> distinct = new HashSet<String>();
        for (int i = 0; i < grams.length; i++) distinct.add(grams[i]);
        return distinct.size() / (double) grams.length;
    }

    /** L03 只出现一次的字组占比：分子是这类字组的<b>个数</b>，分母仍是二元组总次数；空则 0。 */
    public static double hapaxRatio(String text) {
        String[] grams = grams(norm(text));
        if (grams.length == 0) return 0d;
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < grams.length; i++) add(counts, grams[i]);
        int once = 0;
        for (Iterator<Integer> it = counts.values().iterator(); it.hasNext(); )
            if (it.next().intValue() == 1) once++;
        return once / (double) grams.length;
    }

    /** L04 字符熵：归一化后单字频率分布的香农熵，自然对数。 */
    public static double charEntropy(String text) {
        return entropy(charCounts(norm(text), false));
    }

    /** L05 标点熵：只看"非字母数字且不在中日韩汉字区"的那批字符。 */
    public static double punctEntropy(String text) {
        return entropy(charCounts(norm(text), true));
    }

    /** 归一化：去掉所有空白，其余字符原样保留（含补充平面字符，按码点搬）。 */
    static String norm(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            int width = Character.charCount(cp);
            if (!isBlank(cp)) out.appendCodePoint(cp);
            i += width;
        }
        return out.toString();
    }

    /** 按码点滑动的二元组窗口，长度 {@code n-1}；不足两个字给空数组。 */
    private static String[] grams(String normalized) {
        int total = normalized.codePointCount(0, normalized.length());
        if (total < GRAM) return new String[0];
        String[] out = new String[total - GRAM + 1];
        int at = 0;
        for (int i = 0; i < normalized.length(); ) {
            int first = Character.codePointAt(normalized, i);
            int next = i + Character.charCount(first);
            if (next >= normalized.length()) break;      // 最后一个码点没有后继，Python 的 range 也一样短一格
            StringBuilder gram = new StringBuilder(4);
            gram.appendCodePoint(first).appendCodePoint(Character.codePointAt(normalized, next));
            out[at++] = gram.toString();
            i = next;
        }
        return out;
    }

    /**
     * 单字频率分布。{@code punctuationOnly} 为真时丢掉字母数字与中日韩汉字。
     * 用 {@link LinkedHashMap} 是为了让求和顺序与 Python {@code Counter} 的插入顺序（首次出现顺序）
     * 一致——浮点加法不服从结合律，顺序不同末位就可能差出去。
     */
    private static LinkedHashMap<String, Integer> charCounts(String normalized, boolean punctuationOnly) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < normalized.length(); ) {
            int cp = Character.codePointAt(normalized, i);
            i += Character.charCount(cp);
            if (punctuationOnly && (isAlnum(cp) || isHan(cp))) continue;
            add(out, new String(Character.toChars(cp)));
        }
        return out;
    }

    /** {@code -Σ p·ln p}，总数为 0 给 0；求和顺序就是插入顺序。 */
    private static double entropy(LinkedHashMap<String, Integer> counts) {
        long total = 0L;
        for (Iterator<Integer> it = counts.values().iterator(); it.hasNext(); )
            total += it.next().longValue();
        if (total == 0L) return 0d;
        double sum = 0d;
        for (Iterator<Integer> it = counts.values().iterator(); it.hasNext(); ) {
            double p = it.next().doubleValue() / (double) total;
            sum += p * Math.log(p);
        }
        return -sum;
    }

    private static void add(LinkedHashMap<String, Integer> counts, String key) {
        Integer had = counts.get(key);
        counts.put(key, had == null ? Integer.valueOf(1) : Integer.valueOf(had.intValue() + 1));
    }

    /** 对齐 Python {@code str.isalnum()}：字母（L*）、十进制数字（Nd）、字母数字（Nl）、其他数字（No）。 */
    private static boolean isAlnum(int codePoint) {
        if (Character.isLetterOrDigit(codePoint)) return true;   // L* + Nd
        int type = Character.getType(codePoint);
        return type == Character.LETTER_NUMBER || type == Character.OTHER_NUMBER;
    }

    /** Python 那一条 {@code re.match(r"[\u4e00-\u9fff]", ch)}：中日韩统一表意文字的基本区段。 */
    private static boolean isHan(int codePoint) {
        return codePoint >= 0x4E00 && codePoint <= 0x9FFF;
    }

    /**
     * 与 {@code AigcRhythm} 里那条 clean 同一个口径：Unicode 空白全部算空白。
     *
     * <p>与 Python 的 {@code re \s} 逐码点对过，21 个候选里只有一个不一致：U+0085 NEL 在 Python 侧
     * 算空白（会被删掉），Java 侧 {@code isWhitespace} 与 {@code isSpaceChar} 都不认它。U+00A0、
     * U+1680、U+2028、U+2029、U+202F、U+001C..U+001F 两侧都删，U+200B、U+180E、U+FEFF 两侧都留。
     * 黄金样本 40 段里没有 U+0085，这一条对等没被样本钉住，改之前先补样本。
     */
    private static boolean isBlank(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}

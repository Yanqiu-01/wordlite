package com.rikkahub.wordlite;

/**
 * 字符级指纹：滚动 n-gram 哈希 + Robust Winnowing 取样。
 *
 * 句子级比对要求两边恰好以同样的方式断句，改写者只要把两句并成一句就漏掉了。这里换成
 * Schleimer/Wilkerson/Aiken（SIGMOD'03）的 winnowing：任何长度不少于 MIN_MATCH 的公共片段
 * 必定留下一枚相同的指纹，与断句无关。参数按 docs/oss-algorithms.md 里的中文换算取
 * n=8、w=12，最短可报告匹配 19 个字符，相当于一个短句；比原来字符三元组加 Dice 的
 * "一个半词就报重复"严谨得多。
 */
public final class Fingerprints {
    /** 参与取样的最小连续 token 数。 */
    public static final int GRAM = 8;
    /** winnowing 窗口，单位是 token。 */
    public static final int WINDOW = 12;
    /** 数学上保证被取样到的最短公共片段：n + w - 1。 */
    public static final int MIN_MATCH = GRAM + WINDOW - 1;
    private static final long BASE = 0x100000001B3L;
    /** 折平数字之后，所有数目字在哈希里都占这一个格子。 */
    private static final char NUMBER = '#';
    private static final long[] POWERS = powers();

    private Fingerprints() { }

    /** BASE 的幂要备到 GRAM 次：滚出窗口的那枚旧字符，此时正压在 BASE^GRAM 上。 */
    private static long[] powers() {
        long[] out = new long[GRAM + 1];
        out[0] = 1L;
        for (int i = 1; i < out.length; i++) out[i] = out[i - 1] * BASE;
        return out;
    }

    /**
     * 只让汉字、字母和数字参与指纹，标点与空白跳掉；返回它们在原文里的下标。
     * 数字这一段再折一刀：连着出现的阿拉伯数字和中文数目字算同一枚 token，因为"四十分钟"改成
     * "40 分钟"、"七成"改成"70%"是最常见的降重写法和最常见的录入差异，把它当成换了字，
     * 一条连续复制就被砍成几截，谁都不到最短可报告长度。折完之后 1949 年与 1979 年同形，
     * 但指纹只看有没有连续十九枚相同，年份自己撑不起一段重复。
     */
    public static int[] tokens(String norm) {
        if (norm == null || norm.length() == 0) return new int[0];
        int[] out = new int[norm.length()];
        int count = 0;
        boolean inNumber = false;
        for (int i = 0; i < norm.length(); i++) {
            char c = norm.charAt(i);
            if (!readable(c)) continue;
            if (numeral(c)) {
                if (inNumber) continue;
                inNumber = true;
            } else inNumber = false;
            out[count++] = i;
        }
        int[] trimmed = new int[count];
        System.arraycopy(out, 0, trimmed, 0, count);
        return trimmed;
    }

    /** 参与指纹的字符在哈希里的取值：数目字一律折成 NUMBER，比对两端用同一个口径。 */
    static char code(String norm, int at) {
        char c = norm.charAt(at);
        return numeral(c) ? NUMBER : c;
    }

    /** 阿拉伯数字与中文数目字。只收 readable 认得的那些，免得两处口径打架。 */
    static boolean numeral(char c) {
        if (c >= '0' && c <= '9') return true;
        return "零一二三四五六七八九十百千万亿两".indexOf(c) >= 0;
    }

    /** 每个 token 位置上的 n-gram 哈希，不足一元的格子为 0。 */
    public static long[] rolling(String norm, int[] at) {
        int n = at.length;
        long[] out = new long[n];
        long h = 0L;
        for (int i = 0; i < n; i++) {
            h = h * BASE + code(norm, at[i]);
            /* 乘过一次 BASE 之后，最旧那枚字符的位权是 BASE^GRAM 而不是 BASE^(GRAM-1)。
               减错这一位，哈希里就还拖着整段前缀，同一段文字换个位置算出来两样，
               指纹比对只剩下"两边恰好在同一处开头"才命中。 */
            if (i >= GRAM) h -= POWERS[GRAM] * norm.charAt(at[i - GRAM]);
            out[i] = i + 1 >= GRAM ? mix(h) : 0L;
        }
        return out;
    }

    /**
     * Robust Winnowing：每个窗口取最小指纹，并列取最右，只有当这一枚和上次输出的那枚不同才写下。
     * 比的是指纹值而不是窗口下标，这才是"照抄一段必定漏不掉"的前提：完全落在共通区里的那个窗口
     * 两边取到同一枚最小值，即便因为重复被跳过，上次留下的也还是同一枚共通指纹。按窗口下标去重就
     * 没这个保证了——两边历史不同，同一段文字可能一边输出一边不输出，整段照抄也能躲过去。
     * 值相同就跳过，顺带也压住了低熵串（"aaaa"、"abab"）把指纹刷满整篇的毛病。
     * 返回被选中的 token 下标。
     */
    public static int[] sample(long[] hashes) {
        int n = hashes.length;
        if (n < GRAM) return new int[0];
        int[] out = new int[n];
        int count = 0;
        long lastHash = 0L;
        boolean emitted = false;
        for (int end = GRAM - 1; end < n; end++) {
            int from = Math.max(GRAM - 1, end - WINDOW + 1);
            int best = -1;
            for (int i = from; i <= end; i++) {
                if (best < 0 || hashes[i] <= hashes[best]) best = i;   // i 递增，并列时留下最右那一枚
            }
            if (best >= 0 && (!emitted || hashes[best] != lastHash)) {
                out[count++] = best;
                lastHash = hashes[best];
                emitted = true;
            }
        }
        int[] trimmed = new int[count];
        System.arraycopy(out, 0, trimmed, 0, count);
        return trimmed;
    }

    /** 参与指纹的只有汉字、字母和数字：标点与空白跳掉，改写者加个逗号不该躲过检测。 */
    static boolean readable(char c) {
        if (c >= '0' && c <= '9') return true;
        if (c >= 'a' && c <= 'z') return true;
        if (c >= 0x4E00 && c <= 0x9FFF) return true;
        if (c >= 0x3400 && c <= 0x4DBF) return true;
        if (c >= 0xF900 && c <= 0xFAFF) return true;
        return c >= 0xAC00 && c <= 0xD7A3;
    }

    /** splitmix64 收尾，让相邻 n-gram 的哈希散开，避免窗口取最小值时总是同一侧。 */
    static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 文本的指纹集合，供自检使用。 */
    public static long[] of(String norm) {
        int[] at = tokens(norm);
        long[] hashes = rolling(norm, at);
        int[] picked = sample(hashes);
        long[] out = new long[picked.length];
        for (int i = 0; i < picked.length; i++) out[i] = hashes[picked[i]];
        return out;
    }
}
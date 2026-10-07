package com.rikkahub.wordlite;

import java.util.ArrayList;

/**
 * 一把尺子的字符账本（0.7.1）：报告里所有"占多少"的量都从这一份余额出发，分子与分母是同一次划分数出来的字。
 *
 * 0.7.0 之前这份报告为什么能自相矛盾（{@code DuplicateEngine.rates} 原样）：总相似度比的分母是"能切出比对
 * 片段的句子"的有效字符，自编率却又从 100 里减掉一次凭 {@code sentence.score >= 0.5f} 现场圈的 AIGC 句占比。
 * 那把 0.5 的尺检测侧根本不认——区间口径的门槛是 0.45（{@link AigcDetector#SEGMENT_FLAG_GATE}，实测见
 * docs/aigc-calibration.md 的 TIER 行）；而"抄来的"与"机器写的"是两条轴，同一批字可以既重复又机器腔，
 * 放进同一个减法里就互相冲抵。三个比率各量各的，加起来自然不等于 100%。
 *
 * 这里的规矩只有四条：
 * 1. 有效字符只有一个定义：{@link TextCorpus#validCount(String, int, int)} 配 normalize 后的原文——去空白与
 *    不可见字符、按码点数（代理对算一个）。TextCorpus 的分子、{@code AigcFeatures.validChars}、
 *    {@link SourceLedger} 的行内字数用的都是它，所以账本与它们天然同尺，不需要任何换算。
 * 2. 分母是排除区间之外的一切有效字符。每个字符要么记成重复、要么记成自编，不留"这句太短切不出片段所以
 *    两边都不算"的出口——那批字正是三个比率对不上 100% 的去向。
 * 3. 三个桶互斥且闭合：未标引用的重复 + 引用区间内的重复 + 自编 == 分母，于是总相似度比 + 自编率 == 100。
 *    它由同一次按位置划分得出，是恒等式；{@link Balance#residual()} 与 {@link Balance#rateResidual()}
 *    就是拿来把它钉死的，不靠数字凑巧对上。
 * 4. 判"可疑"只有一条门槛 {@link #FLAG_SCORE_GATE}，值直接取检测侧那个常量，账本不再自己另立一条。
 *
 * 纯函数：不检索、不碰界面、不改入参，输入全是原串上的 UTF-16 成对区间。
 */
public final class CharLedger {
    /**
     * 判"可疑"的门槛，取值就是检测侧那条：{@link AigcDetector#SEGMENT_FLAG_GATE} = 0.45，一个数写两处迟早漂移，
     * 所以这里引用而不是抄数字。0.7.0 的账本里躺着的是凭空的 0.5f，同一篇稿子在指标区和证据区各说各话。
     * 0.45 这一档的实测依据：真人论文里 0.45 档的高分句 100% 是孤立的（≥2 连句的字符占比 0.0%），
     * 机写骨架稿同档 4.5%、5/70 段成片——区间才是产品口径，本账本只认过线的区间。
     */
    public static final float FLAG_SCORE_GATE = AigcDetector.SEGMENT_FLAG_GATE;

    /** 账本余额：三个互斥桶 + 一条不参与闭合的机器腔轴，全部是字符绝对量。 */
    public static final class Balance {
        /** 分母：排除区间之外的一切有效字符。 */
        public int totalChars;
        /** 按论文结构排除在比对之外的有效字符（参考文献表、致谢、附录、目录）。 */
        public int excludedChars;
        /** 已判重复的有效字符：命中区间先裁掉排除区、再取并集，一个字符只认一次。 */
        public int duplicateChars;
        /** 其中落在引用区间内的部分。 */
        public int citedDuplicateChars;
        /** duplicateChars - citedDuplicateChars，即"去除引用重复比"的分子。 */
        public int uncitedDuplicateChars;
        /** 减掉全部已判重复字符之后剩下的：totalChars - duplicateChars，不减机器腔字符。 */
        public int selfWrittenChars;
        /** AIGC 可疑区间的有效字符。另一条轴，与重复可以重叠，所以不进闭合、不进自编率。 */
        public int machineChars;
        public double overallRate, excludingCitationsRate, selfWrittenRate, citedDuplicateRate;

        /** 字符闭合残差：三个互斥桶之和减分母，恒为 0。分母为 0 时三个桶也必然为 0，残差同样是 0。 */
        public int residual() {
            return uncitedDuplicateChars + citedDuplicateChars + selfWrittenChars - totalChars;
        }

        /** 同一件事的百分比写法：三个互斥桶的占比之和与 100 的差；没有可计分的字时不成立，返回 0。 */
        public double rateResidual() {
            if (totalChars <= 0) return 0d;
            return excludingCitationsRate + citedDuplicateRate + selfWrittenRate - 100d;
        }

        /** 总相似度比与自编率必须严丝合缝地对咬：这条是报告最容易被用户读错的地方。 */
        public double headlineResidual() {
            if (totalChars <= 0) return 0d;
            return overallRate + selfWrittenRate - 100d;
        }
    }

    private CharLedger() { }

    /**
     * 报告侧入口：TextCorpus 的命中与 AIGC 区间翻成区间后进账本。门槛在这里统一——AIGC 侧只收
     * {@code flagged} 且区间分 {@code >= FLAG_SCORE_GATE} 的区间（两个条件都得成立：flagged 是产品口径，
     * 分数复核是防调用方塞进来自造区间）。查重侧不在这里另立门槛：TextCorpus 判重用的是自己的
     * Dice 下限与最短匹配字数，能进 hits 的区间就是它认的重复。
     */
    public static Balance close(String text, int[] excludedSpans, int[] citedSpans,
                               ArrayList<TextCorpus.Hit> hits, AigcDetector.Result aigc) {
        int[] duplicate = new int[hits == null ? 0 : hits.size() * 2];
        int at = 0;
        if (hits != null)
            for (int i = 0; i < hits.size(); i++) {
                TextCorpus.Hit hit = hits.get(i);
                if (hit == null) continue;
                duplicate[at++] = hit.start;
                duplicate[at++] = hit.end;
            }
        ArrayList<int[]> machine = new ArrayList<int[]>();
        if (aigc != null)
            for (int i = 0; i < aigc.segments.size(); i++) {
                AigcDetector.Segment segment = aigc.segments.get(i);
                if (!segment.flagged || segment.score < FLAG_SCORE_GATE) continue;
                machine.add(new int[]{segment.start, segment.end});
            }
        return closeSpans(text, excludedSpans, citedSpans, duplicate, flatten(machine));
    }

    /**
     * 区间级入口：四个区间都是 {start,end} 成对数组，可为 null；越界、倒置、零长的区间按 mergeSpans 的规矩裁掉，
     * 互相重叠的区间先并再数。返回的 Balance 一定满足 {@link Balance#residual()} == 0，因为三个桶是同一次划分填出来的。
     */
    public static Balance closeSpans(String text, int[] excludedSpans, int[] citedSpans,
                                   int[] duplicateSpans, int[] machineSpans) {
        Balance balance = new Balance();
        if (text == null || text.length() == 0) return balance;
        String norm = TextCorpus.normalize(text);
        int length = text.length();
        int[] excluded = TextCorpus.mergeSpans(excludedSpans, length);
        int[] duplicated = minus(TextCorpus.mergeSpans(duplicateSpans, length), excluded);
        int[] machine = minus(TextCorpus.mergeSpans(machineSpans, length), excluded);
        int[] whole = new int[]{0, length};
        balance.excludedChars = count(norm, excluded);
        balance.totalChars = count(norm, whole) - balance.excludedChars;
        balance.duplicateChars = count(norm, duplicated);
        balance.citedDuplicateChars = count(norm, intersect(duplicated, TextCorpus.mergeSpans(citedSpans, length)));
        balance.uncitedDuplicateChars = balance.duplicateChars - balance.citedDuplicateChars;
        // 自编率的定义就是这句减法：重复字符先扣干净，剩下的才敢说"自己写的"。
        balance.selfWrittenChars = balance.totalChars - balance.duplicateChars;
        balance.machineChars = count(norm, machine);
        if (balance.totalChars > 0) {
            double total = balance.totalChars;
            balance.overallRate = balance.duplicateChars * 100d / total;
            balance.excludingCitationsRate = balance.uncitedDuplicateChars * 100d / total;
            balance.citedDuplicateRate = balance.citedDuplicateChars * 100d / total;
            balance.selfWrittenRate = balance.selfWrittenChars * 100d / total;
        }
        return balance;
    }

    /** 若干区间摊平成成对数组，交给 mergeSpans 去裁去并。 */
    private static int[] flatten(ArrayList<int[]> spans) {
        int[] out = new int[spans.size() * 2];
        for (int i = 0; i < spans.size(); i++) {
            out[i * 2] = spans.get(i)[0];
            out[i * 2 + 1] = spans.get(i)[1];
        }
        return out;
    }

    /** 一组两两不重叠区间里的有效字符总数：因为不重叠，直接相加不会把一个字符数两遍。 */
    private static int count(String norm, int[] spans) {
        int total = 0;
        for (int i = 0; i + 1 < spans.length; i += 2) total += TextCorpus.validCount(norm, spans[i], spans[i + 1]);
        return total;
    }

    /** left 挖掉 right 之后剩下的区间。两个入参都已排序合并，两个指针走一遍就够。 */
    private static int[] minus(int[] left, int[] right) {
        SpanList out = new SpanList();
        for (int i = 0; i + 1 < left.length; i += 2) {
            int cursor = left[i];
            for (int j = 0; j + 1 < right.length && cursor < left[i + 1]; j += 2) {
                if (right[j + 1] <= cursor || right[j] >= left[i + 1]) continue;
                if (right[j] > cursor) out.add(cursor, right[j]);
                cursor = right[j + 1];
            }
            if (cursor < left[i + 1]) out.add(cursor, left[i + 1]);
        }
        return out.toArray();
    }

    /** 两组区间的交集。引用重叠与重复区间都靠它求，两边都是合并过的区间集。 */
    private static int[] intersect(int[] left, int[] right) {
        SpanList out = new SpanList();
        for (int i = 0; i + 1 < left.length; i += 2)
            for (int j = 0; j + 1 < right.length; j += 2) {
                int start = Math.max(left[i], right[j]);
                int end = Math.min(left[i + 1], right[j + 1]);
                if (end > start) out.add(start, end);
            }
        return out.toArray();
    }

    /** 成对区间的极简累加器，省掉 ArrayList<int[]> 的装箱与拆箱。 */
    private static final class SpanList {
        private int[] data = new int[16];
        private int size;

        void add(int start, int end) {
            if (size + 2 > data.length) {
                int[] grown = new int[data.length * 2];
                System.arraycopy(data, 0, grown, 0, size);
                data = grown;
            }
            data[size++] = start;
            data[size++] = end;
        }

        int[] toArray() {
            int[] out = new int[size];
            System.arraycopy(data, 0, out, 0, size);
            return out;
        }
    }
}


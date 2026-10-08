package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * 命中地图的口径：报告详情页顶部那条色带画哪几段、每段多宽、点下去跳到哪，全在这里定。
 *
 * 为什么单独一个类、而且在 Host 侧测：色带的宽度必须与报告里那几个百分比同源。报告的分子是字符账本，
 * 地图的宽度是"每段字符数 / 全文有效字数"，两者只在**四段互不重叠、且都不越过全文末尾**才对得上——那是账，
 * 不是渲染细节，所以钉在 HitMapRegression 里，不许留在 onDraw 里靠肉眼。
 *
 * 四段的优先级（后收的段要减掉前面占掉的字符，减完为空就丢）：重复 > 改写 > AI 可疑 > 疑似 AI。
 * 这个顺序不是审美：重复与改写的字符数已经进过相似率分子，AI 那一档按 CharLedger 的规矩一个字符都不许
 * 再进第二次，所以它只能画在剩下的字上。段与段重叠时谁先占住算谁，与"归属只有一条规矩：先写进 hits 的
 * 那一条得这段字符"同一条。
 */
public final class HitMapModel {

    public static final int KIND_DUPLICATE = 0, KIND_REWRITTEN = 1, KIND_AI = 2, KIND_SUSPECTED = 3;
    /** 每段至少要在带上占住这么多个字才画：一段两个字的碎片在 360dp 宽带上连一个像素都占不满。 */
    public static final int MIN_BAND_CHARS = 3;

    private HitMapModel() { }

    public static final class Band {
        public final int start, end, kind;
        /** 这一段是从哪一条证据 / AI 分段裁下来的（AI 段 sourceIndex 取负，避免与证据下标混起来）。 */
        public final boolean fromEvidence;
        public final int sourceIndex;

        Band(int start, int end, int kind, boolean fromEvidence, int sourceIndex) {
            this.start = start;
            this.end = end;
            this.kind = kind;
            this.fromEvidence = fromEvidence;
            this.sourceIndex = sourceIndex;
        }

        public int chars() { return end - start; }
    }

    public static final class Map {
        public final ArrayList<Band> bands = new ArrayList<Band>();
        public int totalChars;
        public int flaggedChars;
        public int gapChars;
        /** 证据或 AI 分段被上限截过：地图只画了留下那部分，界面必须说出来，不许画成"全文就这些"。 */
        public boolean incomplete;
        public int keptEvidence;
        public int totalEvidence;
        public int keptAi;
        public int totalAi;

        public boolean isEmpty() { return bands.isEmpty(); }

        public double share(Band band) {
            return totalChars <= 0 || band == null ? 0d : (double) band.chars() / (double) totalChars;
        }

        /** 点在某一个字符上：返回那一段的下标，空隙返回 -1。 */
        public int indexAt(int offset) {
            for (int i = 0; i < bands.size(); i++) {
                Band band = bands.get(i);
                if (offset >= band.start && offset < band.end) return i;
            }
            return -1;
        }

        /**
         * 落点在某一个字符上该跳到哪一段：落在段里就是它，落在空隙里取离得最近的那一段。
         * 这条判断放在这里而不是 View 里，因为它是口径不是像素：整条带子几十段，点在两段中间却毫无反应
         * 只会让人以为界面坏了，而空隙本来就没有可跳的目标，所以"跳最近"不存在跳错一段的代价。
         * HitMapRegression 钉它，View 只负责把像素换算成这个偏移。
         */
        public Band nearestBand(int offset) {
            if (isEmpty() || totalChars <= 0) return null;
            int at = offset < 0 ? 0 : (offset >= totalChars ? totalChars - 1 : offset);
            int index = indexAt(at);
            if (index >= 0) return bands.get(index);
            Band best = null;
            int bestGap = Integer.MAX_VALUE;
            for (int i = 0; i < bands.size(); i++) {
                Band band = bands.get(i);
                int gap = at < band.start ? band.start - at : at - band.end;
                if (gap < bestGap) {
                    bestGap = gap;
                    best = band;
                }
            }
            return best;
        }
    }

    /**
     * @param totalChars 全文有效字数（分母，与相似率同一个分母；<=0 时返回一张空图而不是崩）
     * @param evidence   报告留下的证据（带 channel）
     * @param ai         报告留下的 AI 分段
     */
    public static Map build(int totalChars, ArrayList<ReportStore.Evidence> evidence,
                            ArrayList<ReportStore.AiSegment> ai, boolean incomplete,
                            int keptEvidence, int totalEvidence, int keptAi, int totalAi) {
        Map map = new Map();
        map.totalChars = Math.max(0, totalChars);
        map.incomplete = incomplete;
        map.keptEvidence = keptEvidence;
        map.totalEvidence = totalEvidence;
        map.keptAi = keptAi;
        map.totalAi = totalAi;
        if (map.totalChars <= 0) {
            map.gapChars = 0;
            return map;
        }
        ArrayList<int[]> taken = new ArrayList<int[]>();
        // 顺序就是优先级：字面重复、抗改写、AI 可疑、疑似。
        collect(map, taken, evidence, ai, KIND_DUPLICATE, true, false);
        collect(map, taken, evidence, ai, KIND_REWRITTEN, true, false);
        collect(map, taken, evidence, ai, KIND_AI, false, true);
        collect(map, taken, evidence, ai, KIND_SUSPECTED, false, false);
        Collections.sort(map.bands, new Comparator<Band>() {
            public int compare(Band a, Band b) { return a.start != b.start ? a.start - b.start : a.end - b.end; }
        });
        for (int i = 0; i < map.bands.size(); i++) map.flaggedChars += map.bands.get(i).chars();
        map.gapChars = map.totalChars - map.flaggedChars;
        return map;
    }

    private static void collect(Map map, ArrayList<int[]> taken, ArrayList<ReportStore.Evidence> evidence,
                                ArrayList<ReportStore.AiSegment> ai, int kind, boolean fromEvidence,
                                boolean flaggedOnly) {
        if (fromEvidence && evidence != null) {
            for (int i = 0; i < evidence.size(); i++) {
                ReportStore.Evidence hit = evidence.get(i);
                if (hit == null) continue;
                boolean rewrite = hit.channel == TextCorpus.CHANNEL_REWRITE;
                if ((kind == KIND_REWRITTEN) != rewrite) continue;
                add(map, taken, hit.start, hit.end, kind, true, i);
            }
            return;
        }
        if (ai != null) {
            for (int i = 0; i < ai.size(); i++) {
                ReportStore.AiSegment seg = ai.get(i);
                if (seg == null || seg.flagged != flaggedOnly) continue;
                add(map, taken, seg.start, seg.end, kind, false, -(i + 1));
            }
        }
    }

    /** 收下 [from,to) 里还没被更高优先级占走的那些字符。 */
    private static void add(Map map, ArrayList<int[]> taken, int from, int to, int kind,
                            boolean fromEvidence, int sourceIndex) {
        int lo = Math.max(0, from);
        int hi = Math.min(map.totalChars, to);
        if (hi <= lo) return;
        ArrayList<int[]> free = subtract(taken, lo, hi);
        for (int i = 0; i < free.size(); i++) {
            int[] range = free.get(i);
            if (range[1] - range[0] < MIN_BAND_CHARS) continue;
            map.bands.add(new Band(range[0], range[1], kind, fromEvidence, sourceIndex));
            taken.add(range);
        }
    }

    /** [lo,hi) 减掉 taken 里已经占住的每一段（taken 不需要有序，它的规模是个位数到几十）。 */
    private static ArrayList<int[]> subtract(ArrayList<int[]> taken, int lo, int hi) {
        ArrayList<int[]> rest = new ArrayList<int[]>();
        rest.add(new int[] { lo, hi });
        for (int i = 0; i < taken.size(); i++) {
            int[] block = taken.get(i);
            ArrayList<int[]> next = new ArrayList<int[]>();
            for (int r = 0; r < rest.size(); r++) {
                int[] range = rest.get(r);
                if (block[1] <= range[0] || block[0] >= range[1]) {
                    next.add(range);
                    continue;
                }
                if (range[0] < block[0]) next.add(new int[] { range[0], block[0] });
                if (range[1] > block[1]) next.add(new int[] { block[1], range[1] });
            }
            rest = next;
        }
        return rest;
    }
}
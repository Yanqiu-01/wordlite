package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 围绕 AIGC 读数闭环的降重：只改被判可疑的那几段，改完拿**同一件仪器**再量一遍，
 * 整篇可疑字数没真的变小就退回原文。
 *
 * <p>测量口径一条都没新写：整篇前后都是 {@link AigcDetector#detect(String, int[])}，
 * 也就是报告里那个指数与占比用的同一条路（段分按有效字符加权、可疑线是真人段落分 P99）。
 * 段级的"降重前/后"用 {@link AigcRhythm#measure(String, int, int)} 直接读同一段的段分，
 * 与打分那张表是同一份系数，不是另起的一把尺。采纳判定只看**整篇那个数**，段级读数只用来排序和写报告
 * ——同一条纪律见 {@link RewriteLoop} 的类注释：相邻两段各少一点，整篇可能纹丝不动。
 *
 * <p>改写建议出自 {@link AigcTells} 的 {@code cap}（每千字硬上限）与 {@code hint}（改法）两列。
 * 那批套话词表不进分数，但作为改法是对的：它说清"这类说法每千字别超过几处、留几处轻微特征别过度清洁"。
 */
public final class AigcRewrite {

    /** 预算。整篇复测每做一次就是一遍全篇检测，是这条闭环最贵的动作，所以单独卡死。 */
    public static final class Limits {
        public int depth = 6;
        public int candidates = 3;
        public int rounds = 3;
        /** 一篇最多改几个可疑段。 */
        public int regions = 12;
        /** 整篇复测的次数预算；用完还压不下去就照实说预算用完了。 */
        public int verifications = 24;
    }

    /** 一个可疑段的降重账：改前改后各自的段分、以及它有没有被判可疑。 */
    public static final class Region {
        public int start, end;                    // 改完之后这段在正文里的偏移
        public int originStart, originEnd;        // 它在原文里的偏移（整篇退回时按这个重读）
        public int chars;                         // 段内有效字（汉字+字母+数字）
        public double indexBefore, indexAfter;    // 段分，改前 / 改后
        public int flaggedBefore, flaggedAfter;   // 0 或该段字数
        public boolean changed;                   // 采纳的文本与原文不同
        public String original = "", adopted = "";
        public String reason = "";
        /** 这一段的可疑线：段分要超过它才算可疑，报告里要写出来。 */
        public double line = Double.NaN;
    }

    public static final class Result {
        /** 改完的整篇文本；整篇可疑字数没变小就是原文（整篇退回）。 */
        public String text = "";
        /** false = 判据表没装载或正文不够长，前后两个数都不成立。 */
        public boolean measured;
        public String reason = "";
        public int comparedCharsBefore, comparedCharsAfter;
        public double indexBefore, indexAfter;
        public int flaggedBefore, flaggedAfter;
        /** 与 {@link #indexLine()} 同一件仪器、同一条线，可直接并排显示。 */
        public double scaleP99 = Double.NaN;
        public int regions, adoptedRegions;
        public int verifications;
        public final ArrayList<Region> accounts = new ArrayList<Region>();
        /** 降重工单：套话与词表类命中的硬上限与改法，只作改稿线索。 */
        public final ArrayList<AigcTells.Hit> workOrder = new ArrayList<AigcTells.Hit>();
        public String workOrderNote = "";

        /** 整篇指数与可疑字数的并排读法，报告卡与降重结果框共用这一份。 */
        public String indexLine() {
            if (!measured) return reason.length() > 0 ? reason : "量不出降重前后的对比";
            return "AI 倾向指数 " + AigcDetector.indexText(indexBefore) + " → "
                    + AigcDetector.indexText(indexAfter)
                    + "；疑似机器生成 " + flaggedBefore + " 字 → " + flaggedAfter + " 字"
                    + "（占比 " + shareBefore() + "% → " + shareAfter() + "%）";
        }

        public String shareBefore() {
            return AigcDetector.shareText(comparedCharsBefore <= 0 ? 0d
                    : flaggedBefore * 100d / comparedCharsBefore);
        }

        public String shareAfter() {
            return AigcDetector.shareText(comparedCharsAfter <= 0 ? 0d
                    : flaggedAfter * 100d / comparedCharsAfter);
        }

        public String verdict() {
            if (!measured) return reason.length() > 0 ? reason : "量不出降重前后的对比";
            String head = indexLine();
            if (flaggedAfter < flaggedBefore)
                return head + "；采纳 " + adoptedRegions + "/" + regions + " 段的改写，改完重新量过";
            if (flaggedAfter == flaggedBefore && indexAfter < indexBefore)
                return head + "；可疑字数没变，只有指数降了一点，不到半分，这一轮改写不算降下来了";
            return head + "；这一轮改写没有把可疑占比压下半分，正文按原文退回";
        }
    }

    private AigcRewrite() { }

    public static Result run(String text, int[] excluded, List<String> terms) {
        return run(text, excluded, terms, new Limits());
    }

    public static Result run(String text, int[] excluded, List<String> terms, Limits limits) {
        Result out = new Result();
        out.text = text == null ? "" : text;
        if (limits == null) limits = new Limits();
        out.scaleP99 = AigcRhythm.humanP99();
        if (!AigcRhythm.engaged()) {
            out.reason = AigcRhythm.NOT_LOADED;
            return out;
        }
        if (text == null || text.trim().length() == 0) {
            out.reason = "正文是空的，没什么可改";
            return out;
        }
        ArrayList<int[]> holes = spans(excluded);
        AigcDetector.Result first = AigcDetector.detect(out.text, flat(holes));
        out.comparedCharsBefore = first.comparedChars;
        out.indexBefore = first.rate;
        out.flaggedBefore = first.flaggedChars;
        out.workOrder.addAll(AigcTells.workOrder(first.tells));
        out.workOrderNote = AigcTells.describe();
        if (!AigcDetector.indexable(first)) {
            out.reason = AigcDetector.measurement(first);
            out.measured = false;
            return out;
        }
        out.measured = true;
        ArrayList<Region> regions = regions(first, limits.regions);
        out.regions = regions.size();
        if (regions.isEmpty()) {
            // 一段都没过线：什么都没改，改后那两个数就必须等于改前。留成 0 会被 indexLine() 印成
            // "指数 1.3 → 0.0、可疑 0 字 → 0 字"，读起来像"改完归零了"，而这一轮一个字都没动。
            out.comparedCharsAfter = out.comparedCharsBefore;
            out.indexAfter = out.indexBefore;
            out.flaggedAfter = out.flaggedBefore;
            out.reason = AigcDetector.NOT_FOUND + "，没有要改的地方（" + AigcRhythm.WEAK_UNDETECTED + "）";
            return out;
        }
        out.accounts.addAll(regions);

        String working = out.text;
        int verifications = limits.verifications;
        Baseline now = new Baseline(first);
        for (int round = 0; round < limits.rounds && verifications > 0; round++) {
            boolean improved = false;
            // regions 已按正文顺序排好，这里从尾往头走 = 从正文尾往头改：一处改动只会挪动它**后面**的
            // 偏移，而那些段在本轮里已经处理完了，所以还没处理的段用的偏移一直是新鲜的。
            for (int i = regions.size() - 1; i >= 0; i--) {
                Region region = regions.get(i);
                if (verifications <= 0) break;
                if (region.flaggedAfter <= 0) continue;
                // 上一轮已经落笔的段不再回头：段级的改后读数要等整篇跑完才复算（reread），
                // 这一轮再试它，试崩了就把"不改"写进一段已经采纳了的账里，报告与正文自相矛盾。
                if (region.changed) continue;
                String piece = working.substring(region.start, region.end);
                if (region.original.length() == 0) region.original = piece;
                TextProtection.Mask mask = maskOf(piece, terms, region);
                if (mask == null) continue;
                ArrayList<LocalRewriter.Option> options = LocalRewriter.rewrite(mask.submitted,
                        terms, limits.depth, limits.candidates);
                for (int k = 0; k < options.size() && verifications > 0; k++) {
                    String candidate = options.get(k).text;
                    if (candidate == null || candidate.trim().isEmpty()) continue;
                    String applied = mask.restore(candidate);
                    String flaw = flaw(piece, applied);
                    if (flaw != null) {
                        region.reason = flaw;
                        continue;
                    }
                    if (applied.equals(piece)) continue;
                    verifications--;
                    out.verifications++;
                    String trial = working.substring(0, region.start) + applied + working.substring(region.end);
                    AigcDetector.Result after = AigcDetector.detect(trial, flat(holes));
                    if (!better(now, after)) {
                        if (region.reason.length() == 0)
                            region.reason = after.comparedChars < now.chars * 0.95
                                    ? "改完正文短了五分之一以上，不算降重"
                                    : "改完整篇没有变好，这段不改";
                        continue;
                    }
                    // 采纳：整篇换过去，受保护区间的偏移跟着长度变化平移，后面的复测才量在同一把尺上。
                    int delta = applied.length() - (region.end - region.start);
                    shiftRegions(regions, region.end, delta);
                    shift(holes, region.end, delta);
                    working = trial;
                    now.take(after);
                    region.reason = "";
                    region.adopted = applied;
                    region.changed = true;
                    region.end += delta;
                    improved = true;
                    break;
                }
            }
            if (!improved) break;
        }

        AigcDetector.Result last = AigcDetector.detect(working, flat(holes));
        boolean rolledBack = last.flaggedChars > out.flaggedBefore;
        if (rolledBack) {
            // 整篇没变小就整篇退回原文，段级账也按原文重算，不许留一个"看起来改过了"的中间态。
            working = text;
            last = first;
            for (int i = 0; i < regions.size(); i++) {
                Region region = regions.get(i);
                region.changed = false;
                region.adopted = region.original;
                region.start = region.originStart;
                region.end = region.originEnd;
            }
        }
        out.text = working;
        out.comparedCharsAfter = last.comparedChars;
        out.indexAfter = last.rate;
        out.flaggedAfter = last.flaggedChars;
        for (int i = 0; i < regions.size(); i++) {
            Region region = regions.get(i);
            reread(working, region);
            if (region.changed) out.adoptedRegions++;
        }
        return out;
    }

    /** 选这一轮要改的可疑段：按段分从高到低选最多 cap 段，选完再按正文顺序交出去（为什么分两步见方法末尾那句）。 */
    private static ArrayList<Region> regions(AigcDetector.Result first, int cap) {
        ArrayList<AigcDetector.Paragraph> found = new ArrayList<AigcDetector.Paragraph>();
        found.addAll(first.suspiciousParagraphs);
        Collections.sort(found, (a, b) -> Double.compare(b.score, a.score));   // 只按分数"选"段
        ArrayList<Region> out = new ArrayList<Region>();
        for (int i = 0; i < found.size() && out.size() < cap; i++) {
            AigcDetector.Paragraph paragraph = found.get(i);
            Region region = new Region();
            region.originStart = paragraph.start;
            region.originEnd = paragraph.end;
            region.start = paragraph.start;
            region.end = paragraph.end;
            region.chars = paragraph.chars;
            region.indexBefore = paragraph.score;
            region.indexAfter = paragraph.score;
            region.flaggedBefore = paragraph.chars;
            region.flaggedAfter = paragraph.chars;
            region.line = AigcRhythm.humanP99();
            out.add(region);
        }
        // 选完再按正文顺序排回来：主循环从正文尾往头改，这个方向才保证后面的改动不会把前面还没处理的
        // 段的偏移弄过期（分数序与正文序无关，直接按分数序往回走会踩空，越界时抛 StringIndexOutOfBounds）。
        Collections.sort(out, (a, b) -> Integer.compare(a.originStart, b.originStart));
        return out;
    }

    /** 段级掩码：参考文献段不碰，整段都是受保护内容也不碰，理由照实带出来。 */
    private static TextProtection.Mask maskOf(String piece, List<String> terms, Region region) {
        try {
            DocxDocument.ParagraphBlock block = new DocxDocument.ParagraphBlock();
            block.text = piece;
            if (TextProtection.referenceParagraph(block)) {
                region.reason = "参考文献段落不改写";
                return null;
            }
            return TextProtection.mask(block, 0, piece.length(), terms);
        } catch (RuntimeException error) {
            region.reason = "整段都是受保护内容，不改写";
            return null;
        }
    }

    private static final String DEFECT_HEAD = "\uFF0C\u3002\u3001\uFF1B\uFF1A\uFF01\uFF1F\uFF09\uFF3D\u3011\u300B\u201D\u2019";
    private static final String DEFECT_TAIL = "\uFF0C\u3001\uFF1B\uFF1A\uFF01\uFF1F\uFF08\uFF3B\u3010\u300A\u201C\u2018";
    private static final String[] DEFECT_DOUBLES = {
        "\uFF0C\uFF0C", "\u3002\u3002", "\uFF1B\uFF1B", "\uFF0C\u3002", "\u3002\uFF0C",
        "\uFF1B\u3002", "\u3002\uFF1B", "\u3001\u3001", "\uFF1F\uFF1F", "\uFF01\uFF01",
    };

    /** 采纳前的健全性检查：换词可以，把句子改成残缺句不行。与 {@link RewriteLoop} 同一套纪律。 */
    static String flaw(String original, String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) return "改写后是空文本";
        if (DEFECT_HEAD.indexOf(candidate.charAt(0)) >= 0) return "改完以标点开头";
        if (DEFECT_TAIL.indexOf(candidate.charAt(candidate.length() - 1)) >= 0) return "改完以标点结尾";
        for (int i = 0; i < DEFECT_DOUBLES.length; i++)
            if (candidate.contains(DEFECT_DOUBLES[i])) return "出现连续标点 " + DEFECT_DOUBLES[i];
        if (candidate.length() * 10 < original.length() * 6) return "字数缩水超过 40%";
        if (candidate.length() > original.length() * 2) return "字数膨胀超过 100%";
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c >= '\uE000' && c <= '\uF8FF') return "留下了掩码占位符";
        }
        return null;
    }

    /** 一整篇当前的三个读数，采纳判定就对着它比。 */
    /**
     * 一处改动落地之后，改点之后的可疑段偏移跟着平移。originStart/originEnd 不动——那是原文坐标，
     * 整篇退回时要按它重读。少了这一步，这一轮没被采纳的段下一轮再试时用的还是原文偏移，
     * 而正文已经被前面那次采纳改短，substring 直接越界（审计探针量到过）。
     */
    private static void shiftRegions(ArrayList<Region> regions, int at, int delta) {
        if (delta == 0) return;
        for (int i = 0; i < regions.size(); i++) {
            Region region = regions.get(i);
            if (region.start >= at) {
                region.start += delta;
                region.end += delta;
            } else if (region.end > at) region.end += delta;
        }
    }

    private static final class Baseline {
        int runs, chars, flagged;
        double share, index;

        Baseline(AigcDetector.Result result) { take(result); }

        void take(AigcDetector.Result result) {
            runs = result.suspiciousParagraphs.size();
            chars = result.comparedChars;
            flagged = result.flaggedChars;
            share = AigcDetector.share(result);
            index = result.rate;
        }
    }

    /**
     * 采纳判定。第一道闸是**不许靠删内容变好**：正文比改前少 5% 以上直接退回，不然把可疑段落删掉
     * 就能"降重成功"。之后按可疑段数、可疑占比、指数三样依次比，全都持平或更差就不改。
     * 用占比和段数而不是可疑字数当第二道闸，理由同上：字数那个数会被篇幅变化带着走。
     */
    static boolean better(Baseline now, AigcDetector.Result after) {
        if (after.comparedChars < now.chars * 0.95d) return false;
        int runs = after.suspiciousParagraphs.size();
        if (runs < now.runs) return true;
        if (runs > now.runs) return false;
        double share = AigcDetector.share(after);
        // 半分以内的下降不算数：与 DuplicateEngine.compareRewrite 里"相似率基本没动"同一道阈值，
        // 否则改一个标点就能把"降重成功"报出去。
        if (share <= now.share - 0.5d) return true;
        return Math.abs(share - now.share) < 0.5d && now.index - after.rate > 0.05d;
    }

    /** 复测那一段：段分与可疑判定用 {@link AigcRhythm#measure} 直接读，和打分那张表同一个口径。 */
    private static void reread(String text, Region region) {
        int from = Math.max(0, Math.min(text.length(), region.start));
        int to = Math.max(from, Math.min(text.length(), region.end));
        AigcRhythm.Reading reading = AigcRhythm.measure(text, from, to);
        region.chars = reading.validChars;
        if (region.changed) {
            region.indexAfter = reading.score;
            region.flaggedAfter = reading.suspicious ? reading.validChars : 0;
        }
    }

    private static ArrayList<int[]> spans(int[] flat) {
        ArrayList<int[]> out = new ArrayList<int[]>();
        if (flat == null) return out;
        for (int i = 0; i + 1 < flat.length; i += 2)
            if (flat[i + 1] > flat[i]) out.add(new int[]{flat[i], flat[i + 1]});
        return out;
    }

    private static int[] flat(ArrayList<int[]> spans) {
        int[] out = new int[spans.size() * 2];
        for (int i = 0; i < spans.size(); i++) {
            out[i * 2] = spans.get(i)[0];
            out[i * 2 + 1] = spans.get(i)[1];
        }
        return out;
    }

    /** 一处改动落地之后，改点之后的受保护区间整体平移，后面的复测才量在同一把尺上。 */
    private static void shift(ArrayList<int[]> spans, int at, int delta) {
        if (delta == 0) return;
        for (int i = 0; i < spans.size(); i++) {
            int[] span = spans.get(i);
            if (span[0] >= at) {
                span[0] += delta;
                span[1] += delta;
            } else if (span[1] > at) span[1] += delta;
            if (span[1] <= span[0]) {
                spans.remove(i);
                i--;
            }
        }
    }

    /** 一段改完之后在整篇里的位置：写报告时要靠它把段落原文和前后两个读数对上。 */
    public static String slice(String text, Region region) {
        if (text == null || region == null) return "";
        int from = Math.max(0, Math.min(text.length(), region.start));
        int to = Math.max(from, Math.min(text.length(), region.end));
        return text.substring(from, to);
    }

    /** 段级读数的一行人话，报告卡与降重结果框共用。 */
    public static String regionLine(Region region) {
        if (region == null) return "";
        StringBuilder out = new StringBuilder("段内指数 ")
                .append(AigcDetector.indexText(region.indexBefore)).append(" \u2192 ")
                .append(AigcDetector.indexText(region.indexAfter))
                .append(" \u00b7 可疑 ").append(region.flaggedBefore).append(" \u5b57 \u2192 ")
                .append(region.flaggedAfter).append(" \u5b57 \u00b7 ").append(region.chars).append(" \u5b57");
        if (!Double.isNaN(region.line)) out.append(" \u00b7 \u53ef\u7591\u7ebf ").append(String.format(Locale.US, "%.2f", Double.valueOf(region.line)));
        if (region.reason != null && region.reason.length() > 0) out.append(" \u00b7 ").append(region.reason);
        return out.toString();
    }
}

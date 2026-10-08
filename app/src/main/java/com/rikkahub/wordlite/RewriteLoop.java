package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 改写-评分-回退闭环。只对判据已经判为重复的段落出候选，逐候选打分，只采纳让重复率真的变小的那个；
 * 一个候选都没让数字变小，这段就原样留着，并在 verdict 里照实说这段没降下来——不许把没降下来的
 * 改写报成成功（ROADMAP 0.4.7）。
 *
 * 打分口径一条都没新写：
 * - 整篇打分 = TextCorpus.match(text, null, TextCorpus.structure(text).spanArray())，与
 *   DuplicateEngine.compareRewrite 里对改写前后各打一遍的那两句是同一个调用；
 * - 采纳与否只看整篇那个数（命中字数）有没有变小。段落级的读数只用来给候选排序与写报告，
 *   不当采纳依据：命中区间间隔不超过 MERGE_GAP 就会并成一段，相邻段落各自少命中几个字，
 *   整篇的数纹丝不动，甚至变大（2026-10-09 在真稿靶子上实测到 3.14% 变 4.15%）。
 * - 不动区完好 = TextProtection.Mask.restore()，与离线改写界面用的是同一个掩码。
 * 阈值、判据、字符账本都在 TextCorpus/CharLedger 那边，这个类里一个数字都不定义。
 */
public final class RewriteLoop {

    /** 预算。整篇级验证每做一次就是一遍全篇比对，是这条闭环最贵的动作，所以单独卡死。 */
    public static final class Limits {
        /** 一轮里让每条规则最多改几个匹配点（>1 只在这条闭环里用，候选必须过掩码再过判据）。 */
        public int depth = 6;
        /** 每轮最多评几个候选（LocalRewriter 最多给 3 个，外加长句拆短）。 */
        public int candidates = 3;
        /** 一个命中区最多深化几轮。 */
        public int rounds = 4;
        /** 一篇最多处理几个命中区。 */
        public int regions = 12;
        /** 整篇级验证的次数预算；用完还压不下去就照实说预算用完了。 */
        public int verifications = 24;
    }

    /** 一个"被判定重复的段落"的账：改前改后各自单独对着语料打的分。 */
    public static final class Segment {
        public int start, end;
        public String original = "", adopted = "";
        /** 采纳的文本与原文不同。 */
        public boolean changed;
        /** 这段单独对着语料的命中字数，改前/改后。 */
        public int dupBefore, dupAfter;
        /** 这段单独比对时的最高相似度分，改前/改后。 */
        public float scoreBefore, scoreAfter;
        /** 改完之后这段单独比对已经没有命中。 */
        public boolean cleared;
        public String reason = "";
    }

    /** 一片连成一片的命中区（可能覆盖相邻的几个段落）。采纳只看它对整篇数字的影响。 */
    public static final class Region {
        public int start, end;
        public int paragraphs;
        /** 采纳这一段改动前后，整篇的命中字数。 */
        public int dupBefore, dupAfter;
        public boolean improved;
        public int rounds, tried, verified, rejected;
        public String strategy = "", reason = "";
    }

    /** 回写到文档用的一条替换：段落在 selection 里的位置与替换文本。 */
    public static final class Edit {
        public final int paragraphIndex, start, end;
        public final String replacement;
        Edit(int paragraphIndex, int start, int end, String replacement) {
            this.paragraphIndex = paragraphIndex; this.start = start; this.end = end; this.replacement = replacement;
        }
    }

    public static final class Result {
        /** 改完的整篇文本；一个区都没压下去时它等于原文（回退）。 */
        public String text = "";
        /** false = 没有可比对基线或可比对字数为 0，前后两个数都不成立。 */
        public boolean measured;
        public String reason = "";
        public int comparedChars, dupBefore, dupAfter, hitsBefore, hitsAfter;
        public double rateBefore, rateAfter;
        public int regions, regionsImproved, segments, segmentsChanged, segmentsCleared;
        /** 整篇级验证用了多少次，以及是不是用完了预算。 */
        public int verified;
        /** true = 调用方在 Listener.cancelled() 里说要停，这一轮提前收尾（已采纳的改动保留）。 */
        public boolean cancelled;
        /** 被文本健全性断言挡下的候选数（改完是残句/空段/缩水过多）。 */
        public int rejected;
        public boolean budgetHit;
        public final ArrayList<Region> regionList = new ArrayList<Region>();
        public final ArrayList<Segment> details = new ArrayList<Segment>();
        public DuplicateEngine.RewriteDelta delta;
        public String verdict = "";
        public double drop() { return rateBefore - rateAfter; }
    }

    private RewriteLoop() { }

    public static Result run(String text, TextCorpus corpus, List<String> terms) {
        return run(text, corpus, terms, new Limits(), null);
    }

    public static Result run(String text, TextCorpus corpus, List<String> terms, Limits limits) {
        return run(text, corpus, terms, limits, null);
    }

    /** 判据本身：与 DuplicateEngine.compareRewrite 里对 before/after 的调用逐字相同。 */
    private static TextCorpus.Report judge(TextCorpus corpus, String text) {
        return corpus.match(text, null, TextCorpus.structure(text).spanArray());
    }

    /**
     * 界面接入用的进度与取消回调。整个 run() 是阻塞的，必须在后台线程调，回调也就在同一个后台线程
     * 上发生——实现方要刷界面自己 post 回主线程，这里不碰任何 Android 东西。
     * 两个方法都会被反复调用，所以都不许阻塞、不许抛（抛了会把整轮改写打断）。
     */
    public interface Listener {
        /** stage = 现在在干什么；done/total 是整篇级验证的次数进度，total 未知时给 -1。 */
        void onProgress(String stage, int done, int total);

        /** 每出一批候选、每做一次整篇验证之前各问一次。返回 true 就收尾返回已经采纳的结果。 */
        boolean cancelled();
    }

    /** 进度与取消的转发器：允许传 null，回调抛异常一律咽掉。 */
    private static final class Pump {
        private final Listener listener;
        int verified, budget;

        Pump(Listener listener, int budget) {
            this.listener = listener;
            this.budget = budget;
        }

        void stage(String what, int done) { stage(what, done, budget); }

        void stage(String what, int done, int total) {
            if (listener == null) return;
            try {
                listener.onProgress(what, done, total);
            } catch (RuntimeException ignored) {
            }
        }

        boolean cancelled() {
            if (listener == null) return false;
            try {
                return listener.cancelled();
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    }

    private static float bestScore(TextCorpus.Report report) {
        float best = 0f;
        for (TextCorpus.Hit hit : report.hits) if (hit.score > best) best = hit.score;
        return best;
    }

    public static Result run(String text, TextCorpus corpus, List<String> terms, Limits limits,
                             Listener listener) {
        Result out = new Result();
        out.text = text == null ? "" : text;
        if (limits == null) limits = new Limits();
        if (text == null || text.isEmpty() || corpus == null || corpus.isEmpty()) {
            out.reason = "没有可比对基线语料";
            out.verdict = "先做一次查重或先导入自建库，才知道改写有没有用";
            return out;
        }
        Pump pump = new Pump(listener, Math.max(1, limits.verifications));
        pump.stage("对着语料比对原文", 0);
        TextCorpus.Report before = judge(corpus, text);
        out.comparedChars = before.comparedChars;
        out.rateBefore = before.overallRate;
        out.dupBefore = before.duplicateChars;
        out.hitsBefore = before.hits.size();
        out.measured = before.comparedChars > 0;
        if (!out.measured) {
            out.reason = "可比对字数不足";
            out.verdict = "可比对字数不足，衡量不出改写效果";
            return out;
        }
        if (before.hits.isEmpty()) {
            out.rateAfter = out.rateBefore;
            out.verdict = "这篇对着当前语料没有命中，不改写";
            return out;
        }

        pump.stage("判出 " + before.hits.size() + " 处命中", 0);
        ArrayList<int[]> blocks = paragraphs(text);
        ArrayList<int[]> ranges = regions(blocks, before.hits, limits.regions, out);
        String working = text;
        int current = before.duplicateChars;
        int budget = Math.max(1, limits.verifications);
        for (int index = 0; index < ranges.size(); index++) {
            if (out.verified >= budget) { out.budgetHit = true; break; }
            if (pump.cancelled()) { out.cancelled = true; break; }
            int[] range = ranges.get(index);
            int from = range[0], to = range[1];
            String region = working.substring(from, to);
            Region account = new Region();
            account.start = from;
            account.end = to;
            account.paragraphs = count(region, '\n') + 1;
            account.dupBefore = current;
            out.regionList.add(account);
            out.regions++;
            pump.stage("改写第 " + (index + 1) + "/" + ranges.size() + " 个命中区", out.verified);
            Search search = search(corpus, working, from, to, region, terms, limits, account,
                    budget - out.verified, current, pump);
            out.verified += search.verified;
            pump.verified = out.verified;
            if (search.cancelled) out.cancelled = true;
            current = search.duplicateChars;
            if (!search.text.equals(region)) {
                working = working.substring(0, from) + search.text + working.substring(to);
                account.improved = true;
                out.regionsImproved++;
                int grown = search.text.length() - (to - from);
                for (int[] other : ranges) {           // 后面的区间跟着文本长度挪
                    if (other[0] >= to) { other[0] += grown; other[1] += grown; }
                }
            }
            account.dupAfter = current;
            out.rejected += account.rejected;
        }
        if (out.verified >= budget) out.budgetHit = true;

        out.text = working;
        if (!working.equals(text)) {
            DuplicateEngine.RewriteDelta delta = DuplicateEngine.compareRewrite(text, working, corpus);
            out.delta = delta;
            out.rateBefore = delta.beforeRate;
            out.rateAfter = delta.afterRate;
            out.dupBefore = delta.beforeDuplicate;
            out.dupAfter = delta.afterDuplicate;
            out.comparedChars = delta.afterCompared;
        } else {
            out.rateAfter = out.rateBefore;
            out.dupAfter = out.dupBefore;
        }
        pump.stage("复核改写结果", out.verified);
        out.hitsAfter = judge(corpus, working).hits.size();
        diagnose(out, corpus, blocks, text, working);
        out.verdict = verdict(out);
        return out;
    }

    private static final class Search {
        String text;
        int verified, rounds, tried, rejected, duplicateChars;
        boolean cancelled;
        String defect = "";
    }

    /**
     * 一个命中区：区里每个"单独比对仍命中"的段落各出一批候选（离线规则 + 长句拆短）。段级读数只用来
     * 排序，采纳只看整篇的命中字数——段里少几个字而整篇不变甚至变大是实测到的（相邻命中会并段，
     * TextCorpus.MERGE_GAP=2），所以判定必须留在整篇这一遍。只有让整篇数字严格变小的候选才留下；
     * 一段都压不下去就原样返回，reason 里照实写为什么。
     */
    private static Search search(TextCorpus corpus, String whole, int from, int to, String region,
                                 List<String> terms, Limits limits, Region account, int budget, int baseline,
                                 Pump pump) {
        Search out = new Search();
        out.text = region;
        out.duplicateChars = baseline;
        final int global = pump.verified;
        int slots = paragraphs(region).size();
        int[] adopted = new int[slots];
        String skip = "";
        while (out.verified < budget) {
            if (pump.cancelled()) { out.cancelled = true; skip = "调用方取消，提前收尾"; break; }
            pump.stage("在命中区里出候选并逐篇验证", global + out.verified, global);
            if (paragraphs(out.text).size() != slots) { skip = "段落数变了，停止深化"; break; }
            ArrayList<int[]> order = weighted(corpus, out.text);
            boolean moved = false;
            for (int[] row : order) {
                if (adopted[row[0]] >= Math.max(1, limits.rounds)) continue;
                if (out.verified >= budget) break;
                String piece = out.text.substring(row[1], row[2]);
                TextCorpus.Report here = judge(corpus, piece);      // 段级当前读数，同一个 match 调用
                StringBuilder why = new StringBuilder();
                TextProtection.Mask mask = maskOf(piece, terms, why);
                if (mask == null) { if (skip.isEmpty()) skip = why.toString(); continue; }
                String masked = mask.submitted;
                ArrayList<String[]> options = new ArrayList<String[]>();
                for (LocalRewriter.Option option : LocalRewriter.rewrite(masked, terms, limits.depth, limits.candidates))
                    options.add(new String[]{ option.text, option.strategy, "" });
                for (LocalRewriter.Option option : splits(masked, limits.candidates))
                    options.add(new String[]{ option.text, option.strategy, "" });
                for (String[] option : rank(corpus, mask, options, out)) {
                    if (out.verified >= budget) break;
                    String candidate;
                    try {
                        candidate = mask.restore(option[0]);            // 不动区没原样保住就在这里抛
                    } catch (RuntimeException ignored) {
                        continue;
                    }
                    String flaw = defect(piece, candidate);
                    if (flaw != null) {
                        out.rejected++;
                        if (out.defect.isEmpty()) out.defect = flaw;
                        continue;
                    }
                    String next = out.text.substring(0, row[1]) + candidate + out.text.substring(row[2]);
                    out.verified++;
                    int dup = judge(corpus, whole.substring(0, from) + next + whole.substring(to))
                            .duplicateChars;
                    // 只认"整篇命中字数变小"会瞎：判据把命中段落整段计数，改词改到 0.61 分段里字数还是
                    // 一个字没少（实测 dup 32->32、score 0.983->0.610）。所以同字数时看段级的两个读数
                    // 有没有往前走——还是同一个 match，没有第二条口径。整篇数字绝不允许变大。
                    if (dup > out.duplicateChars) continue;
                    if (dup == out.duplicateChars && !advance(option[2], here)) continue;
                    out.duplicateChars = dup;
                    out.text = next;
                    out.rounds++;
                    adopted[row[0]]++;
                    account.strategy = joined(account.strategy, option[1]);
                    moved = true;
                    break;                       // 这段先改到这里，回到外层按新的段级读数重排
                }
                if (moved) break;
            }
            if (!moved) break;
        }
        account.verified = out.verified;
        account.rejected = out.rejected;
        account.tried = out.tried;
        if (out.cancelled) account.reason = "调用方取消，这段之后的命中区没再验证";
        String spoiled = out.rejected > 0
                ? "；" + out.rejected + " 个候选被文本健全性断言挡下（" + out.defect + "）" : "";
        if (account.reason.isEmpty()) {
            if (out.text.equals(region)) {
                account.reason = out.tried == 0
                        ? (skip.isEmpty() ? "没有适用的离线规则" : skip)
                        : out.tried + " 个候选都没让整篇命中字数变小" + spoiled;
            } else {
                account.reason = out.rounds + " 次采纳，整篇命中字数 " + account.dupBefore
                        + " -> " + out.duplicateChars + spoiled;
            }
        }
        return out;
    }

    /** 段级掩码。参考文献段、整段都是受保护内容时返回 null，并把原因带出去。 */
    private static TextProtection.Mask maskOf(String piece, List<String> terms, StringBuilder why) {
        try {
            DocxDocument.ParagraphBlock block = new DocxDocument.ParagraphBlock();
            block.text = piece;
            if (TextProtection.referenceParagraph(block)) {
                why.append("参考文献段落不改写");
                return null;
            }
            return TextProtection.mask(block, 0, piece.length(), terms);
        } catch (RuntimeException error) {
            why.append("整段都是受保护内容，不改写");
            return null;
        }
    }

    /**
     * 同字数时的进步判定：option[2] 是 rank() 用判据本身算出的段级读数 "命中字数|处数|最高分"。
     * 命中字数变少算进步；字数没变但最高分掉下阈值一档也算进步，因为下一次改词就是接着这个底子改。
     */
    private static boolean advance(String reading, TextCorpus.Report now) {
        if (reading == null || reading.isEmpty()) return false;
        String[] parts = reading.split("\\|");
        int dup = Integer.parseInt(parts[0]);
        float score = parts.length > 2 ? Float.parseFloat(parts[2]) : 0f;
        float best = bestScore(now);
        return dup < now.duplicateChars || (dup == now.duplicateChars && score < best - 0.01f);
    }

    /** 排序用：只排"单独比对仍然命中"的段落，命中字数多的先改。判定不在这里做。 */
    private static ArrayList<int[]> weighted(TextCorpus corpus, String region) {
        ArrayList<int[]> out = new ArrayList<int[]>();
        ArrayList<int[]> blocks = paragraphs(region);
        for (int i = 0; i < blocks.size(); i++) {
            int dup = judge(corpus, region.substring(blocks.get(i)[0], blocks.get(i)[1])).duplicateChars;
            if (dup > 0) out.add(new int[]{ i, blocks.get(i)[0], blocks.get(i)[1], dup });
        }
        Collections.sort(out, (a, b) -> Integer.compare(b[3], a[3]));
        return out;
    }

    private static final String DEFECT_HEAD = "，。、；：！？）］】》”’";
    private static final String DEFECT_TAIL = "，、；：！？（［【《“‘";
    private static final String[] DEFECT_DOUBLES = {
        "，，", "。。", "；；", "，。", "。，", "；。", "。；", "、、", "？？", "！！", "，、", "、，",
    };

    /**
     * 采纳前的文本健全性断言：换词可以，把句子改成残缺句不行。深模式的规则一旦产出以标点开头、
     * 连续标点、或字数掉掉一大截的文本，这个候选直接丢掉——整篇数字变小也不能采纳。
     */
    private static String defect(String original, String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) return "改写后是空文本";
        if (DEFECT_HEAD.indexOf(candidate.charAt(0)) >= 0) return "改完以标点开头";
        if (DEFECT_TAIL.indexOf(candidate.charAt(candidate.length() - 1)) >= 0) return "改完以标点结尾";
        for (String pair : DEFECT_DOUBLES)
            if (candidate.contains(pair)) return "出现连续标点 " + pair;
        if (candidate.length() * 10 < original.length() * 6) return "字数缩水超过 40%";
        if (candidate.length() > original.length() * 2) return "字数膨胀超过 100%";
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c >= '\uE000' && c <= '\uF8FF') return "留下了掩码占位符";
        }
        return null;
    }

    /** 段落级读数只用来排序：先比命中字数，再比最高分。采纳判定在整篇那一遍。 */
    private static ArrayList<String[]> rank(TextCorpus corpus, TextProtection.Mask mask,
                                            ArrayList<String[]> options, Search out) {
        ArrayList<String[]> kept = new ArrayList<String[]>();
        Set<String> seen = new HashSet<String>();
        for (String[] option : options) {
            if (!seen.add(option[0])) continue;
            out.tried++;
            try {
                String restored = mask.restore(option[0]);
                TextCorpus.Report local = judge(corpus, restored);
                option[2] = local.duplicateChars + "|" + local.hits.size() + "|" + bestScore(local);
            } catch (RuntimeException ignored) {
                continue;                                    // 掩码不让过：这个候选根本不参与
            }
            kept.add(option);
        }
        final ArrayList<String[]> rows = new ArrayList<String[]>(kept);
        Collections.sort(rows, (a, b) -> Integer.compare(localDup(a), localDup(b)));
        return rows;
    }

    private static int localDup(String[] option) {
        int at = option[2].indexOf('|');
        return Integer.parseInt(at < 0 ? option[2] : option[2].substring(0, at));
    }

    /** 句子边界：与 TextCorpus 切句用的是同一批句号级标点。 */
    private static final String SENTENCE_END = "。！？；\n\r";
    /** 逗号后面是这些字就不拆：拆完把连接词甩到句首，读起来是断的。 */
    private static final String NO_SPLIT_AFTER = "但是而并且且就也又则或和与及因为所以下不没未在对于把被将";
    /** 逗号前面是这些字就不拆：拆完左边是个残句。 */
    private static final String NO_SPLIT_BEFORE = "的下之在与及对於于把将由于因为虽然如果为了通过根据当以及包括例如即如而且";
    /** 左边以这些词开头就不拆：状语从句被拆断以后主句没有着落。 */
    private static final String[] NO_SPLIT_HEADS = {
        "由于", "因为", "虽然", "尽管", "如果", "为了", "通过", "根据", "随着", "在", "当", "不仅", "既然",
    };
    /** 拆完两边都要能独立成句。 */
    private static final int MIN_CLAUSE_CHARS = 12;

    /**
     * 长句拆短：把一处"，"换成"；"。整段只动一个标点，数字/单位/引文序号/术语一个字都没动，
     * 掩码占位符的位置也没挪，所以它天然过"不动区完好"这一关；它买的是比对单元的切分。
     */
    private static ArrayList<LocalRewriter.Option> splits(String masked, int limit) {
        ArrayList<LocalRewriter.Option> out = new ArrayList<LocalRewriter.Option>();
        for (int at = masked.indexOf('，'); at > 0 && out.size() < Math.max(1, limit);
             at = masked.indexOf('，', at + 1)) {
            if (!splittable(masked, at)) continue;
            LocalRewriter.Option option = new LocalRewriter.Option();
            option.text = masked.substring(0, at) + '；' + masked.substring(at + 1);
            option.strategy = "拆句";
            out.add(option);
        }
        return out;
    }

    private static boolean splittable(String text, int at) {
        int left = at, right = at + 1;
        while (left > 0 && SENTENCE_END.indexOf(text.charAt(left - 1)) < 0) left--;
        int stop = right;
        while (stop < text.length() && SENTENCE_END.indexOf(text.charAt(stop)) < 0) stop++;
        if (at - left < MIN_CLAUSE_CHARS || stop - right < MIN_CLAUSE_CHARS) return false;
        if (NO_SPLIT_AFTER.indexOf(text.charAt(right)) >= 0) return false;
        if (NO_SPLIT_BEFORE.indexOf(text.charAt(at - 1)) >= 0) return false;
        String head = text.substring(left, Math.min(left + 2, at));
        for (String word : NO_SPLIT_HEADS) if (head.equals(word)) return false;
        return true;
    }

    /** 改完之后逐段再各打一遍，报告里"这段没降下来"要用段级的数说话。 */
    private static void diagnose(Result out, TextCorpus corpus, ArrayList<int[]> blocks,
                                 String original, String working) {
        ArrayList<int[]> after = paragraphs(working);
        boolean aligns = after.size() == blocks.size();
        for (int i = 0; i < blocks.size(); i++) {
            String oldText = original.substring(blocks.get(i)[0], blocks.get(i)[1]);
            TextCorpus.Report local = judge(corpus, oldText);
            Segment segment = new Segment();
            segment.start = blocks.get(i)[0];
            segment.end = blocks.get(i)[1];
            segment.original = oldText;
            segment.dupBefore = local.duplicateChars;
            segment.scoreBefore = bestScore(local);
            segment.dupAfter = segment.dupBefore;
            segment.scoreAfter = segment.scoreBefore;
            if (aligns) {
                String newText = working.substring(after.get(i)[0], after.get(i)[1]);
                TextCorpus.Report now = judge(corpus, newText);
                segment.adopted = newText;
                segment.changed = !newText.equals(oldText);
                segment.dupAfter = now.duplicateChars;
                segment.scoreAfter = bestScore(now);
                segment.cleared = now.duplicateChars == 0 && !newText.equals(oldText);
            }
            segment.reason = segment.changed
                    ? (segment.cleared ? "改完单独比对已经没有命中"
                            : "改了，但单独比对仍命中 " + segment.dupAfter + " 字（最高分 "
                                    + String.format(Locale.US, "%.3f", segment.scoreAfter) + "）")
                    : "这段没动";
            out.details.add(segment);
            if (segment.changed) out.segmentsChanged++;
            if (segment.cleared) out.segmentsCleared++;
        }
    }

    private static String verdict(Result out) {
        if (!out.measured) return out.verdict;

        StringBuilder line = new StringBuilder();
        line.append("重复率 ").append(DuplicateEngine.percent(out.rateBefore)).append(" -> ")
                .append(DuplicateEngine.percent(out.rateAfter))
                .append("（命中 ").append(out.dupBefore).append('/').append(out.comparedChars)
                .append(" 字 -> ").append(out.dupAfter).append('/').append(out.comparedChars)
                .append(" 字，降 ").append(DuplicateEngine.percent(out.drop())).append(" 个百分点）");
        line.append("；").append(out.hitsBefore).append(" 处命中，处理 ").append(out.regions)
                .append(" 个命中区、采纳 ").append(out.regionsImproved).append(" 个；")
                .append(out.segmentsChanged).append(" 个段落文本有变化，其中 ")
                .append(out.segmentsCleared).append(" 段改完单独比对已经没有命中");
        if (out.rejected > 0) line.append("；").append(out.rejected)
                .append(" 个候选被文本健全性断言挡下，没进采纳判定");
        if (out.budgetHit) line.append("（整篇验证预算 ").append(out.verified).append(" 次用完，剩下的命中区没验证）");
        if (out.regionsImproved == 0) line.append("。这一轮一个字都没换，重复率没降");
        if (out.cancelled) line.append("（调用方取消，提前收尾；留下的每一处改动都是让整篇命中字数变小才被采纳的）");
        return line.toString();
    }

    /** 段落边界 = TextSelection.all 拼接用的那个换行；返回的偏移是整篇文本里的偏移。 */
    static ArrayList<int[]> paragraphs(String text) {
        ArrayList<int[]> out = new ArrayList<int[]>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '\n') continue;
            if (i > start) out.add(new int[]{start, i});
            start = i + 1;
        }
        if (text.length() > start) out.add(new int[]{start, text.length()});
        return out;
    }

    /** 命中覆盖到的段落，相邻的并成一个区：命中区间隔不超过 MERGE_GAP 就会并段，分开改没有意义。 */
    private static ArrayList<int[]> regions(ArrayList<int[]> blocks, ArrayList<TextCorpus.Hit> hits,
                                            int cap, Result out) {
        ArrayList<Integer> covered = new ArrayList<Integer>();
        for (int i = 0; i < blocks.size(); i++)
            if (overlaps(blocks.get(i), hits)) covered.add(i);
        ArrayList<int[]> out2 = new ArrayList<int[]>();
        int index = 0;
        while (index < covered.size()) {
            int first = covered.get(index), last = first;
            int next = index + 1;
            while (next < covered.size() && covered.get(next) == covered.get(next - 1) + 1) last = covered.get(next++);
            index = next;
            if (out2.size() >= Math.max(1, cap)) { out.budgetHit = true; continue; }
            out2.add(new int[]{ blocks.get(first)[0], blocks.get(last)[1] });
        }
        out.segments = covered.size();
        return out2;
    }

    private static boolean overlaps(int[] block, ArrayList<TextCorpus.Hit> hits) {
        for (TextCorpus.Hit hit : hits) if (hit.end > block[0] && hit.start < block[1]) return true;
        return false;
    }

    private static int count(String text, char c) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == c) total++;
        return total;
    }

    private static String joined(String old, String add) {
        if (add == null || add.isEmpty() || old.contains(add)) return old;
        return old.isEmpty() ? add : old + "、" + add;
    }

    /** 采纳了的整篇文本按段落回写给界面：段落数必须没变，变了就不回写。 */
    public static ArrayList<Edit> edits(TextSelection selection, Result out, String original) {
        ArrayList<Edit> edits = new ArrayList<Edit>();
        if (selection == null || out == null || out.text.equals(original)) return edits;
        ArrayList<int[]> before = paragraphs(original), after = paragraphs(out.text);
        if (before.size() != after.size()) return edits;
        for (int i = 0; i < before.size(); i++) {
            String oldText = original.substring(before.get(i)[0], before.get(i)[1]);
            String newText = out.text.substring(after.get(i)[0], after.get(i)[1]);
            if (oldText.equals(newText)) continue;
            ArrayList<TextSelection.Range> ranges = selection.ranges(before.get(i)[0], before.get(i)[1]);
            if (ranges.size() != 1) continue;
            TextSelection.Range range = ranges.get(0);
            edits.add(new Edit(range.paragraphIndex, range.start, range.end, newText));
        }
        return edits;
    }

    /** 逐段读数，界面与断言都读这个，不再自己算第二遍。 */
    public static List<Segment> details(Result out) {
        return out == null ? Collections.<Segment>emptyList() : Collections.unmodifiableList(out.details);
    }
}

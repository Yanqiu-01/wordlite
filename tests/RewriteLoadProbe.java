package com.rikkahub.wordlite;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 降重负载曲线的量具：把 N 句插进样稿，量改写前/改写后的重复率、命中段数、被改写覆盖的比例、
 * 字数变化。插句、取正文、打分全部复用 RewriteRateRegression 里那几个方法，所以这里的数
 * 和那条断言的数同口径，能直接连成一条曲线。
 *
 * 与那条断言的分工：RewriteRateRegression 钉住 11 句那个点的真值不许漂；这里负责把
 * 11 / 25 / 45 三个负载点量出来写进 docs/rewrite-rate-measurement.md，不判成败。
 *
 * 用法：java -cp <classes> com.rikkahub.wordlite.RewriteLoadProbe \
 *          tests/samples/input-liu.docx <句子文件> <输出目录> [rounds] [depth] [候选数] [每段验证预算] [术语文件]
 */
public final class RewriteLoadProbe {

    private static final Pattern DIGITS = Pattern.compile("[0-9]+(?:[.．][0-9]+)?");
    private static final Pattern CITATION = Pattern.compile("\\[[0-9０-９]+(?:\\s*[-–,，、]\\s*[0-9０-９]+)*\\]");
    private static final Pattern MODEL = Pattern.compile("[A-Za-z]{2,}[0-9]+[A-Za-z0-9-]*");

    public static void main(String[] args) throws Exception {
        String source = args.length > 0 ? args[0] : "tests/samples/input-liu.docx";
        String sentenceFile = args.length > 1 ? args[1] : "tests/corpus/oa-planted-cjmenet.txt";
        String outDir = args.length > 2 ? args[2] : "artifacts/tests/rewrite-load";
        Files.createDirectories(Paths.get(outDir));
        List<String> sentences = RewriteRateRegression.lines(sentenceFile);
        int chars = 0;
        for (String s : sentences) chars += s.length();

        List<String> terms = args.length > 7 ? RewriteRateRegression.lines(args[7])
                : new ArrayList<String>(RewriteRateRegression.TERMS);

        TextCorpus corpus = new TextCorpus();
        TextCorpus.Source src = new TextCorpus.Source();
        src.id = "local:load-" + sentences.size();
        src.title = "自建库：负载点 " + sentences.size() + " 句";
        StringBuilder joined = new StringBuilder();
        for (String s : sentences) joined.append(s).append("。\n");
        corpus.add(src, joined.toString());

        String target = outDir + "/planted-" + sentences.size() + ".docx";
        RewriteRateRegression.plant(source, sentences, target);
        String before = RewriteRateRegression.text(target);
        TextCorpus.Report b = RewriteRateRegression.judge(corpus, before);

        int perSegment = args.length > 6 ? Integer.parseInt(args[6]) : 24;
        RewriteLoop.Limits limits = new RewriteLoop.Limits();
        limits.rounds = args.length > 3 ? Integer.parseInt(args[3]) : 10;
        limits.depth = args.length > 4 ? Integer.parseInt(args[4]) : 8;
        limits.candidates = args.length > 5 ? Integer.parseInt(args[5]) : 8;
        limits.verifications = perSegment * Math.max(1, b.hits.isEmpty() ? 1 : sentences.size());

        long began = System.currentTimeMillis();
        RewriteLoop.Result r = RewriteLoop.run(before, corpus, terms, limits);
        long elapsed = System.currentTimeMillis() - began;

        int changed = 0, cleared = 0, untouchedHits = 0;
        float worst = 0f, bestAfter = 1f;
        int stillAbove = 0;
        int[] buckets = new int[4];      // 改完仍命中的段按最高分分档：<0.50 / 0.50-0.70 / 0.70-0.85 / >0.85
        for (RewriteLoop.Segment s : RewriteLoop.details(r)) {
            if (s.dupBefore == 0 && s.dupAfter == 0) continue;
            if (s.changed) changed++;
            if (s.cleared) cleared++;
            if (!s.changed) untouchedHits++;
            if (s.dupAfter > 0) {
                stillAbove++;
                buckets[s.scoreAfter < 0.60f ? 0 : s.scoreAfter < 0.72f ? 1 : s.scoreAfter < 0.85f ? 2 : 3]++;
                worst = Math.max(worst, s.scoreAfter);
                bestAfter = Math.min(bestAfter, s.scoreAfter);
            }
        }
        // 词表覆盖率：这些命中段里有几句是离线规则一条都碰不到的（与闭环采纳与否无关，只看有没有候选）。
        int noRule = 0, hitSegments = 0;
        StringBuilder noRuleSample = new StringBuilder();
        ArrayList<int[]> blocks = RewriteLoop.paragraphs(before);
        for (int[] block : blocks) {
            String piece = before.substring(block[0], block[1]);
            if (RewriteRateRegression.judge(corpus, piece).duplicateChars == 0) continue;
            hitSegments++;
            if (LocalRewriter.rewrite(piece, terms, limits.depth, limits.candidates).isEmpty()) {
                noRule++;
                if (noRule <= 3) noRuleSample.append("｜").append(piece);
            }
        }
        double charDelta = 100d * (r.text.length() - before.length()) / before.length();
        int dBefore = RewriteRateRegression.hitsOf(DIGITS, before).size();
        int dAfter = RewriteRateRegression.hitsOf(DIGITS, r.text).size();
        int cBefore = RewriteRateRegression.hitsOf(CITATION, before).size();
        int cAfter = RewriteRateRegression.hitsOf(CITATION, r.text).size();
        int mBefore = RewriteRateRegression.hitsOf(MODEL, before).size();
        int mAfter = RewriteRateRegression.hitsOf(MODEL, r.text).size();

        System.out.println("LOAD 句子 " + sentences.size() + " 句 / " + chars + " 字；预算 每段 "
                + perSegment + " 次整篇验证（合计上限 " + limits.verifications + "）");
        System.out.println("BEFORE 重复率 " + RewriteRateRegression.pct(r.rateBefore) + " 命中 "
                + r.dupBefore + "/" + r.comparedChars + " 字，命中处 " + r.hitsBefore
                + "，覆盖段落 " + r.segments + " 段，文本字数 " + before.length());
        System.out.println("AFTER 重复率 " + RewriteRateRegression.pct(r.rateAfter) + " 命中 "
                + r.dupAfter + "/" + r.comparedChars + " 字，命中处 " + r.hitsAfter
                + "，降幅 " + RewriteRateRegression.pct(r.drop()) + " 个百分点（相对少 "
                + String.format(Locale.ROOT, "%.1f%%", 100d * (1d - r.rateAfter / r.rateBefore)) + "）");
        System.out.println("COVER 覆盖 " + r.segments + " 段：改写覆盖 " + changed + " 段（"
                + String.format(Locale.ROOT, "%.0f%%", 100d * changed / Math.max(1, r.segments))
                + "），改完已无命中 " + cleared + " 段，一个字没动 " + untouchedHits + " 段");
        System.out.println("REMAIN 仍命中 " + stillAbove + " 段，改后最高分区间 "
                + String.format(Locale.US, "%.3f-%.3f", bestAfter > worst ? 0f : bestAfter, worst)
                + "；分档 0.50-0.60 " + buckets[0] + " 段｜0.60-0.72 " + buckets[1]
                + " 段｜0.72-0.85 " + buckets[2] + " 段｜>0.85 " + buckets[3] + " 段");
        System.out.println("NORULE 命中 " + hitSegments + " 段里离线规则一条都碰不到的 " + noRule + " 段"
                + (noRuleSample.length() == 0 ? "" : noRuleSample.toString()));
        System.out.println("CHARS 文本字数 " + before.length() + " -> " + r.text.length() + "（"
                + String.format(Locale.ROOT, "%+.2f%%", charDelta) + "）");
        System.out.println("ISLANDS 数字 " + dBefore + "->" + dAfter + "，引文序号 " + cBefore + "->" + cAfter
                + "，型号 " + mBefore + "->" + mAfter + "，术语表 " + terms.size() + " 个");
        System.out.println("COST 整篇验证 " + r.verified + " 次（预算 " + limits.verifications
                + (r.budgetHit ? "，已用完" : "，没用完") + "），健全性断言挡下 " + r.rejected
                + " 个候选，耗时 " + elapsed + "ms");
        System.out.println("VERDICT " + r.verdict);
        if (System.getProperty("RewriteLoadProbe.segments") != null) {
            for (RewriteLoop.Segment s : RewriteLoop.details(r)) {
                if (s.dupBefore == 0 && s.dupAfter == 0) continue;
                System.out.println("SEGMENT 命中 " + s.dupBefore + "->" + s.dupAfter + "，最高分 "
                        + String.format(Locale.US, "%.3f->%.3f", s.scoreBefore, s.scoreAfter)
                        + (s.cleared ? "  已清零" : (s.changed ? "  仍命中" : "  没动")));
            }
        }
    }
}

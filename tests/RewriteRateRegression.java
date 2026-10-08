package com.rikkahub.wordlite;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 这个类里的 plant/text/judge/lines 等是给 RewriteLoadProbe 共用的：负载曲线那三个点
 * 必须和这里的断言走同一套插句、取正文、打分代码，不然前后数字不可比。
 *
 * 一条能量化的回归断言：仓库里的 11 句真开放获取论文正文（tests/corpus/oa-planted-cjmenet.txt）当固定语料，
 * 插进 tests/samples/input-liu.docx 得到靶子，跑 RewriteLoop（改写-评分-回退），断言
 * "改写前 X% -> 改写后 Y%"、命中处数、改写覆盖了几个命中段、字数变化百分比，并且数字、单位、
 * 引文序号、型号、登记术语一字未动。前后两个百分数打进断言输出，不是只打印"通过"。
 *
 * 全程不联网，也不改仓库里的原稿：靶子 docx 只写到输出目录（默认 artifacts/tests/rewrite-rate/）。
 * 打分口径一个都没新写——判据就是 TextCorpus.match 那一句，与 DuplicateEngine.compareRewrite 逐字相同。
 *
 * 用法（host JVM）：
 *   java -cp <classes> com.rikkahub.wordlite.RewriteRateRegression \
 *       tests/samples/input-liu.docx tests/corpus/oa-planted-cjmenet.txt artifacts/tests/rewrite-rate
 */
public final class RewriteRateRegression {

    /** 登记术语：掩码后一个字都不许动，断言里前后各数一遍出现次数。 */
    static final List<String> TERMS =
            Arrays.asList("锯齿形切屑", "切削变形区", "绝热剪切带");
    /** 单位与符号：改写前后逐个计数。 */
    private static final String[] UNITS = {
        "mm", "cm", "μm", "um", "nm", "MPa", "GPa", "kPa", "Pa", "Hz", "kHz", "℃", "%", "r/min", "mL",
    };
    private static final Pattern DIGITS = Pattern.compile("[0-9]+(?:[.．][0-9]+)?");
    private static final Pattern CITATION = Pattern.compile("\\[[0-9０-９]+(?:\\s*[-–,，、]\\s*[0-9０-９]+)*\\]");
    private static final Pattern MODEL = Pattern.compile("[A-Za-z]{2,}[0-9]+[A-Za-z0-9-]*");

    private static int count;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    static String pct(double rate) {
        return String.format(Locale.ROOT, "%.2f%%", rate);
    }

    static List<String> hitsOf(Pattern pattern, String text) {
        List<String> out = new ArrayList<String>();
        Matcher m = pattern.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    static List<String> lines(String path) throws Exception {
        List<String> out = new ArrayList<String>();
        for (String line : new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8).split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
        }
        return out;
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 把句子作为新的首段插进 docx 的 body 开头；只写出副本，原稿不动。 */
    static void plant(String docx, List<String> sentences, String out) throws Exception {
        java.util.LinkedHashMap<String, byte[]> entries = DocxZipReader.read(new FileInputStream(docx));
        StringBuilder paras = new StringBuilder();
        for (String s : sentences)
            paras.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(esc(s)).append("。</w:t></w:r></w:p>");
        String xml = new String(entries.get("word/document.xml"), StandardCharsets.UTF_8);
        int cut = xml.indexOf("<w:body>") + "<w:body>".length();
        entries.put("word/document.xml",
                (xml.substring(0, cut) + paras + xml.substring(cut)).getBytes(StandardCharsets.UTF_8));
        ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out));
        for (String name : entries.keySet()) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(entries.get(name));
            zip.closeEntry();
        }
        zip.close();
    }

    static DocxDocument document(String docx) throws Exception {
        InputStream in = new FileInputStream(docx);
        try {
            return DocxParser.parse(in, docx);
        } finally {
            in.close();
        }
    }

    static String text(String docx) throws Exception {
        return TextSelection.all(document(docx)).text;
    }

    /** 判据本身：与 DuplicateEngine.compareRewrite 里的两句调用逐字相同。 */
    static TextCorpus.Report judge(TextCorpus corpus, String text) {
        return corpus.match(text, null, TextCorpus.structure(text).spanArray());
    }

    static float bestScore(TextCorpus.Report report) {
        float best = 0f;
        for (TextCorpus.Hit hit : report.hits) if (hit.score > best) best = hit.score;
        return best;
    }

    public static void main(String[] args) throws Exception {
        String source = args.length > 0 ? args[0] : "tests/samples/input-liu.docx";
        String sentenceFile = args.length > 1 ? args[1] : "tests/corpus/oa-planted-cjmenet.txt";
        String outDir = args.length > 2 ? args[2] : "artifacts/tests/rewrite-rate";
        Files.createDirectories(Paths.get(outDir));

        List<String> sentences = lines(sentenceFile);
        check(sentences.size() == 11, "固定语料是 11 句（实得 " + sentences.size() + "）");

        TextCorpus corpus = new TextCorpus();
        TextCorpus.Source src = new TextCorpus.Source();
        src.id = "local:oa-planted-cjmenet";
        src.title = "自建库：钛铝合金低温切削（开放获取，11 句派生）";
        StringBuilder joined = new StringBuilder();
        for (String s : sentences) joined.append(s).append("。\n");
        corpus.add(src, joined.toString());
        check(corpus.sentenceCount() == 11, "语料入库 11 句（实得 " + corpus.sentenceCount() + "）");

        String planted = text(source);
        check(hitsOf(DIGITS, planted).size() > 0, "未插句的原稿里有数字（"
                + hitsOf(DIGITS, planted).size() + " 处），不动区断言不是空断言");

        // 负对照：同一个库对着没插句的原稿必须 0 命中，否则后面的前后对比不成立。
        TextCorpus.Report control = judge(corpus, planted);
        System.out.println("NOTE 负对照（原稿 vs 同一语料） " + pct(control.overallRate) + " "
                + control.duplicateChars + "/" + control.comparedChars + " 字，命中 " + control.hits.size() + " 处");
        check(control.hits.isEmpty() && control.duplicateChars == 0,
                "负对照：原稿 0 命中（实测 " + control.hits.size() + " 处，" + pct(control.overallRate) + "）");

        String targetFile = outDir + "/input-liu-planted.docx";
        plant(source, sentences, targetFile);
        String before = text(targetFile);
        System.out.println("NOTE 靶子文本字数 " + before.length() + "（原稿 " + planted.length() + "）");

        TextCorpus.Report b = judge(corpus, before);
        double rateBefore = b.overallRate;
        System.out.println("NOTE 改写前 " + pct(rateBefore) + " 命中 " + b.duplicateChars + "/"
                + b.comparedChars + " 字，" + b.hits.size() + " 处，最高分 "
                + String.format(Locale.US, "%.3f", bestScore(b)));
        check(b.duplicateChars == 516, "改写前真值钉在 516 字（实测 " + b.duplicateChars + "）");
        check(b.hits.size() == 1, "改写前 1 处命中（实测 " + b.hits.size() + "）");
        check(rateBefore >= 3.0, "改写前重复率 ≥ 3.00%（实测 " + pct(rateBefore) + "）");

        RewriteLoop.Limits limits = new RewriteLoop.Limits();
        limits.rounds = 10;
        limits.depth = 8;
        limits.candidates = 8;
        limits.verifications = 240;
        long began = System.currentTimeMillis();
        RewriteLoop.Result r = RewriteLoop.run(before, corpus, TERMS, limits);
        long elapsed = System.currentTimeMillis() - began;

        double rateAfter = r.rateAfter;
        int changed = 0, cleared = 0;
        for (RewriteLoop.Segment s : RewriteLoop.details(r)) {
            if (s.changed) changed++;
            if (s.cleared) cleared++;
        }
        double charsDelta = 100d * (r.text.length() - before.length()) / before.length();
        System.out.println("RESULT 重复率 " + pct(rateBefore) + " -> " + pct(rateAfter)
                + "（降 " + pct(r.drop()) + " 个百分点，相对降 "
                + String.format(Locale.ROOT, "%.1f%%", 100d * (1d - rateAfter / rateBefore)) + "）");
        System.out.println("RESULT 命中字数 " + r.dupBefore + "/" + r.comparedChars + " -> "
                + r.dupAfter + "/" + r.comparedChars + "；命中处数 " + r.hitsBefore + " -> " + r.hitsAfter);
        System.out.println("RESULT 覆盖命中段 " + r.segments + " 段，其中改写出候选 " + changed
                + " 段、改完单独比对已无命中 " + cleared + " 段");
        System.out.println("RESULT 文本字数 " + before.length() + " -> " + r.text.length() + "（"
                + String.format(Locale.ROOT, "%+.2f%%", charsDelta) + "）；整篇验证 " + r.verified
                + " 次，健全性断言挡下 " + r.rejected + " 个候选，耗时 " + elapsed + "ms");
        System.out.println("RESULT 判定 " + r.verdict);

        for (RewriteLoop.Segment s : RewriteLoop.details(r)) {
            if (s.dupBefore == 0 && s.dupAfter == 0) continue;
            System.out.println("SEGMENT 命中字数 " + s.dupBefore + " -> " + s.dupAfter + "，最高分 "
                    + String.format(Locale.US, "%.3f", s.scoreBefore) + " -> "
                    + String.format(Locale.US, "%.3f", s.scoreAfter)
                    + (s.cleared ? "  已清零" : (s.changed ? "  仍命中" : "  没动")) + "｜" + s.adopted);
        }

        check(r.measured, "闭环量出了前后两个数");
        check(rateAfter < rateBefore, "改写后严格低于改写前（" + pct(rateAfter) + " < " + pct(rateBefore) + "）");
        check(r.dupAfter * 100 <= r.dupBefore * 85,
                "命中字数至少少一成半（" + r.dupBefore + " -> " + r.dupAfter + "，实测少 "
                        + String.format(Locale.ROOT, "%.1f%%", 100d - 100d * r.dupAfter / r.dupBefore) + "）");
        check(r.drop() >= 0.5, "绝对降幅 ≥ 0.5 个百分点（实测 " + pct(r.drop()) + "）");
        // 命中"处数"不是单调量：判据把相隔不超过 MERGE_GAP 的命中并成一段（TextCorpus.MERGE_GAP=2），
        // 改完中间的段落不再命中，一条长命中带就裂成几条短的。1 处变 3 处是真的，重复字数仍在降。
        System.out.println("NOTE 命中处数 " + r.hitsBefore + " -> " + r.hitsAfter
                + "（并段裂开，不是命中变多；量它的是上面的重复字数）");
        check(r.hitsAfter >= 1, "改完仍有命中（" + r.hitsAfter + " 处），闭环没把它报成零");
        check(r.segments == 11, "命中覆盖 11 个段落（实测 " + r.segments + "）");
        check(changed >= 8, "至少 8 个命中段落被改写覆盖（实测 " + changed + "）");
        check(!r.budgetHit, "整篇验证预算没用完（用了 " + r.verified + " 次）");
        check(Math.abs(r.text.length() - before.length()) * 100 <= before.length() * 2,
                "整篇字数变化在 2% 以内（" + String.format(Locale.ROOT, "%+.2f%%", charsDelta) + "）");

        // 不动区：数字、引文序号、型号、单位、登记术语，整篇前后逐个对齐。
        check(hitsOf(DIGITS, before).equals(hitsOf(DIGITS, r.text)),
                "数字一字未动（前后各 " + hitsOf(DIGITS, before).size() + " 处，序列逐位相同）");
        check(hitsOf(CITATION, before).equals(hitsOf(CITATION, r.text)),
                "引文序号一字未动（前后各 " + hitsOf(CITATION, before).size() + " 处）");
        check(hitsOf(MODEL, before).equals(hitsOf(MODEL, r.text)),
                "型号一字未动（前后各 " + hitsOf(MODEL, before).size() + " 处）");
        for (String unit : UNITS) {
            int x = occurrences(before, unit), y = occurrences(r.text, unit);
            if (x != y) throw new AssertionError("单位 " + unit + " 前后不一致：" + x + " -> " + y);
        }
        count++;
        System.out.println("PASS 单位与符号逐类计数前后一致（" + UNITS.length + " 类）");
        for (String term : TERMS) {
            int x = occurrences(before, term), y = occurrences(r.text, term);
            check(x == y && x > 0, "登记术语 " + term + " 前后 " + x + " -> " + y + " 次");
        }

        // 掩码这条路与界面用的是同一个：改完的段落必须能过 restore。
        ArrayList<int[]> beforeBlocks = RewriteLoop.paragraphs(before);
        ArrayList<int[]> afterBlocks = RewriteLoop.paragraphs(r.text);
        check(beforeBlocks.size() == afterBlocks.size(), "段落数没变（" + afterBlocks.size() + "）");
        int restored = 0;
        for (int i = 0; i < beforeBlocks.size(); i++) {
            String oldText = before.substring(beforeBlocks.get(i)[0], beforeBlocks.get(i)[1]);
            String newText = r.text.substring(afterBlocks.get(i)[0], afterBlocks.get(i)[1]);
            if (oldText.equals(newText)) continue;
            DocxDocument.ParagraphBlock block = new DocxDocument.ParagraphBlock();
            block.text = oldText;
            TextProtection.Mask mask = TextProtection.mask(block, 0, oldText.length(), TERMS);
            check(islandsKept(mask, newText), "第 " + (i + 1) + " 段 " + mask.islands.size()
                    + " 处不动区在改写结果里按原样、按顺序各出现一次");
            restored++;
            check(!newText.startsWith("，") && !newText.startsWith("。") && !newText.contains("，，")
                            && !newText.contains("。。"),
                    "第 " + (i + 1) + " 段改完不是残句");
        }
        check(restored >= 8, "每个被改的段落都过了掩码（" + restored + " 段）");
        check(occurrences(r.text, "⟦") == 0 && placeholders(r.text) == 0, "改写结果里没有占位符残留");

        // "不许把没降下来的改写报成成功"：声称已清零的段，单独重测必须真的 0 命中。
        for (RewriteLoop.Segment s : RewriteLoop.details(r)) {
            if (!s.cleared) continue;
            int dup = judge(corpus, s.adopted).duplicateChars;
            check(dup == 0, "声称已清零的段落复测命中 " + dup + " 字");
        }
        check(r.verdict.contains(pct(rateBefore)) && r.verdict.contains(pct(rateAfter)),
                "判定句里带着前后两个百分数，不给只写了成功的判定");

        // 界面入口的形状：进度、取消、回写用的替换条目，都拿真靶子走一遍。
        final ArrayList<String> stages = new ArrayList<String>();
        final ArrayList<Integer> dones = new ArrayList<Integer>();
        final int[] asked = new int[1];
        RewriteLoop.Result cut = RewriteLoop.run(before, corpus, TERMS, limits, new RewriteLoop.Listener() {
            @Override public void onProgress(String stage, int done, int total) {
                if (stages.isEmpty() || !stages.get(stages.size() - 1).equals(stage)) stages.add(stage);
                if (!dones.isEmpty() && done < dones.get(dones.size() - 1))
                    throw new AssertionError("进度倒退：" + dones.get(dones.size() - 1) + " -> " + done);
                dones.add(done);
            }
            @Override public boolean cancelled() { return ++asked[0] > 8; }
        });
        System.out.println("NOTE 取消那一轮 " + pct(cut.rateBefore) + " -> " + pct(cut.rateAfter)
                + "，命中字数 " + cut.dupBefore + " -> " + cut.dupAfter + "，整篇验证 " + cut.verified
                + " 次（跑完要 " + r.verified + " 次），进度阶段：" + String.join(" → ", stages));
        check(cut.cancelled, "取消被认下来（Result.cancelled=true）");
        check(cut.verified * 3 <= r.verified, "取消确实提前收尾（" + cut.verified + " 次 vs 跑完 " + r.verified + " 次）");
        check(cut.rateAfter <= cut.rateBefore,
                "取消那一轮没让重复率变高（" + pct(cut.rateAfter) + " <= " + pct(cut.rateBefore) + "）");
        check(cut.text.equals(before) || cut.dupAfter < cut.dupBefore,
                "取消那一轮留下的改动都只在整篇命中字数变小时才被采纳");
        check(cut.verdict.contains("取消"), "取消那一轮的判定句照实说提前收尾");
        check(stages.contains("对着语料比对原文") && stages.contains("复核改写结果"),
                "进度回调覆盖首尾（" + stages.size() + " 个阶段）");

        ArrayList<RewriteLoop.Edit> edits = RewriteLoop.edits(TextSelection.all(document(targetFile)), r, before);
        int lastParagraph = -1;
        boolean ordered = true;
        for (RewriteLoop.Edit edit : edits) {
            if (edit.paragraphIndex <= lastParagraph) ordered = false;
            lastParagraph = edit.paragraphIndex;
        }
        System.out.println("NOTE 回写给界面的替换条目 " + edits.size() + " 条，段落号从 "
                + (edits.isEmpty() ? "-" : String.valueOf(edits.get(0).paragraphIndex)) + " 起");
        check(edits.size() == changed, "回写条数与被改的段落数对上（" + edits.size() + " = " + changed + "）");
        check(ordered, "回写按段落号递增，界面可以从后往前替换而不串位");

        // 反向一条：没有任何规则能让数字变小的时候，必须照实说没降，并且一个字都不回写。
        String stuck = "本文研究了工艺参数对接头质量的影响。";
        TextCorpus stuckCorpus = new TextCorpus();
        TextCorpus.Source ss = new TextCorpus.Source();
        ss.id = "local:stuck";
        ss.title = "自建库：无可适用规则";
        stuckCorpus.add(ss, stuck);
        RewriteLoop.Result none = RewriteLoop.run(stuck, stuckCorpus, TERMS, limits);
        System.out.println("NOTE 无适用规则的那一段 " + pct(none.rateBefore) + " -> " + pct(none.rateAfter)
                + "，采纳 " + none.regionsImproved + " 个区，判定：" + none.verdict);
        check(none.rateBefore == none.rateAfter && none.regionsImproved == 0,
                "改不动的那一段：数字没变、采纳数为 0");
        check(none.text.equals(stuck), "改不动的那一段：文本原样退回");
        check(none.verdict.contains("没降"), "改不动的那一段：判定句照实说没降");

        System.out.println("SUMMARY " + count + " assertions passed；重复率 "
                + pct(rateBefore) + " -> " + pct(rateAfter));
    }

    /**
     * 独立复算一遍不动区：闭环里用的是 mask.restore 那道守卫，这里另走一条路——掩码给出的每一处
     * 受保护内容（数字、单位、型号、引文序号、登记术语），必须在改写后的段落里按原样、按顺序出现。
     */
    private static boolean islandsKept(TextProtection.Mask mask, String after) {
        int cursor = 0;
        for (TextProtection.Island island : mask.islands) {
            int at = after.indexOf(island.original, cursor);
            if (at < 0) return false;
            cursor = at + island.original.length();
        }
        return true;
    }

    static int occurrences(String text, String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) found++;
        return found;
    }

    private static int placeholders(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xE000 && c <= 0xF8FF) n++;   // 掩码用的私有区字符
        }
        return n;
    }
}

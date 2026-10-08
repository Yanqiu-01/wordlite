package com.rikkahub.wordlite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 整篇端到端实测台：跑的是产品那条链路（{@code DuplicateEngine.scan} 的两阶段检索 + 全篇比对），
 * 打出来的就是用户在报告页上看到的那几个数。
 *
 * <p>它存在的理由是 RecallProbe 漏掉的半条路：那台探针自己把候选塞进语料，于是"检索取回多少条"
 * 和"最后有几条真进了比对"被混成一个数。2026-10-08 真机那篇 19967 字的开题报告就是这么蒙过去的——
 * 逐句召回实测 66.7%，报告页却写着总相似度比 0.00%：第二阶段拿整篇压出来的四十八个字排序，
 * 中段窗口问回来的候选一个三元组都对不上，全被零分闸门丢在语料之外。
 *
 * <p>这台机器只做三件事：① 把真句种进稿子里；② 让产品链路自己跑完；③ 把入库账目、三个比率、
 * 以及"旧的排序式今天会饿死几条"一起打出来。它不在闸门里，一轮几十个真请求，见
 * {@code tools/full-scan-probe.ps1}。
 */
public final class FullScanProbe {
    /** 与逐句探针同一把尺：短于这个数有效字的摘要句子不算一句完整的话。 */
    private static final int MIN_SEED_VALID_CHARS = 22;
    /** 两次真提问之间的间隔，与生产链路同一理由：别把共享的匿名配额一次打光。 */
    private static final long REQUEST_GAP_MILLIS = 400L;

    private FullScanProbe() { }

    public static void main(String[] argv) throws Exception {
        String proxy = argv.length > 0 ? argv[0].trim() : "";
        int per = argv.length > 1 ? Integer.parseInt(argv[1]) : 12;
        String engineList = argv.length > 2 ? argv[2] : "cnki,wanfang,cqvip";
        String query = argv.length > 3 ? argv[3]
                : "碳化硅 瞬态液相扩散焊 界面组织 中间层 接头性能";
        String filler = filler(argv.length > 4 ? argv[4] : "tests/corpus/real-prose.txt");
        int seedsPerEngine = argv.length > 5 ? Integer.parseInt(argv[5]) : 2;
        int windows = argv.length > 6 ? Integer.parseInt(argv[6]) : 12;

        String[] engines = engineList.split(",");
        PaperSources.Limits limits = new PaperSources.Limits();
        limits.perEngine = per;
        limits.windows = windows;
        limits.timeoutSeconds = 25;
        limits.proxy = proxy;
        System.out.println("probe per_engine_page=" + per + " windows=" + windows + " engines=" + engineList
                + " proxy=" + (proxy.isEmpty() ? "直连优先" : proxy));

        ArrayList<PaperSources.Candidate> seeds = new ArrayList<PaperSources.Candidate>();
        ArrayList<String> sentences = new ArrayList<String>();
        for (String raw : engines) {
            String engine = raw.trim();
            if (engine.isEmpty()) continue;
            List<PaperSources.Candidate> found = ask(engine, query, limits);
            if (found == null) {
                System.out.println("== " + engine + " 提问失败，这一轮的种子少一家");
                continue;
            }
            int used = 0;
            for (PaperSources.Candidate seed : found) {
                if (used >= seedsPerEngine) break;
                String sentence = seedSentence(seed);
                if (sentence == null) continue;
                used++;
                seeds.add(seed);
                sentences.add(sentence);
            }
            System.out.println("== " + engine + " 取回 " + found.size() + " 条，从中种下 " + used + " 句");
        }
        if (sentences.isEmpty()) {
            System.out.println("NOTE 一句都没种成——摘要太短或检索全空，这台机器没跑起来，别把下面的数当结论。");
            System.exit(2);
        }

        String text = manuscript(filler, sentences);
        DocxDocument document = new DocxDocument();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            DocxDocument.ParagraphBlock block = new DocxDocument.ParagraphBlock();
            block.index = i;
            block.text = lines[i];
            document.blocks.add(block);
            document.paragraphs.add(block);
        }

        TextCorpus corpus = new TextCorpus();
        long began = System.currentTimeMillis();
        DuplicateEngine.Report report = DuplicateEngine.scan(TextSelection.all(document), corpus, true,
                listOf(engines), limits, new ApiClient.Task(), null);
        long took = System.currentTimeMillis() - began;

        StringBuilder asked = new StringBuilder();
        for (String engine : report.windowsAsked.keySet())
            asked.append(engine).append('=').append(report.windowsAsked.get(engine)).append(' ');
        StringBuilder admitted = new StringBuilder();
        for (String engine : report.candidateCount.keySet())
            admitted.append(engine).append('=').append(report.candidateCount.get(engine)).append(' ');
        System.out.println("INTAKE 窗口 " + report.windowsRetrieved + "/" + report.windowsPlanned + "/"
                + report.windowsAvailable + "，覆盖 " + report.coveredChars + "/" + report.comparableChars + " 字");
        System.out.println("INTAKE 每源提问次数 " + asked.toString().trim());
        System.out.println("INTAKE 入库 " + report.candidates.size() + " 条（可比 " + report.comparableCandidates
                + "，抓到全文 " + report.fullTextCandidates + "，只有题录 " + report.recordOnlyCandidates
                + "），零分被挡 " + report.unrankedCandidates + " 条，跨源并掉 " + report.mergedDuplicates + " 篇");
        System.out.println("INTAKE 逐源入库 " + admitted.toString().trim());

        /* 反事实：还按"整篇压成四十八个字"排序的话，本次真进语料的这些条会有几条被零分挡掉。 */
        String whole = PaperSources.queryPhrase(report.sourceText, DuplicateEngine.MAX_PHRASE_CHARS);
        int starved = 0;
        ArrayList<PaperSources.Candidate> pool = new ArrayList<PaperSources.Candidate>();
        pool.addAll(report.candidates);
        for (CandidateRanker.Scored scored : CandidateRanker.rank(whole, pool))
            if (scored.score <= 0d) starved++;
        System.out.println("OLD-RANKING 旧排序式检索式=[" + cut(whole, 48) + "]，本次入库的 "
                + report.candidates.size() + " 条里会有 " + starved + " 条被零分挡掉");

        System.out.println(String.format(Locale.US,
                "RATES 总相似度比=%.2f%% 排除引用=%.2f%% 自编率=%.2f%% AIGC=%.2f%% 比对 %d 字 / 重复 %d 字 / 命中 %d 段",
                Double.valueOf(report.overallRate), Double.valueOf(report.excludingCitationsRate),
                Double.valueOf(report.selfWrittenRate), Double.valueOf(report.aigcRate),
                Integer.valueOf(report.comparedChars), Integer.valueOf(report.duplicateChars),
                Integer.valueOf(report.hits.size())));
        System.out.println("RATES 耗时 " + took + "ms（引擎内计时 " + report.elapsedMillis + "ms）"
                + (report.retrievalIncomplete ? " 未完成" : "") + (report.retrievalPartial ? " 部分完成" : ""));
        for (int i = 0; i < report.notes.size() && i < 6; i++)
            System.out.println("NOTE " + report.notes.get(i));

        TextCorpus.Report matched = corpus.match(text, null, TextCorpus.structure(text).spanArray());
        int flagged = 0, attributed = 0;
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            int at = text.indexOf(sentence);
            TextCorpus.Hit over = at < 0 ? null : covering(matched, at, at + sentence.length());
            boolean isFlagged = over != null;
            boolean isAttributed = isFlagged && samePaper(over.source, seeds.get(i).source);
            if (isFlagged) flagged++;
            if (isAttributed) attributed++;
            System.out.println(String.format(Locale.US, "  plant[%d] flagged=%-5s attributed=%-5s 出处=[%s] 句=[%s]",
                    Integer.valueOf(i), Boolean.valueOf(isFlagged), Boolean.valueOf(isAttributed),
                    cut(over == null ? null : over.source.title, 24), cut(sentence, 40)));
        }
        System.out.println(String.format(Locale.US,
                "SUMMARY plants=%d 入库=%d 零分被挡=%d flagged=%d attributed=%d 总相似度比=%.2f%%",
                Integer.valueOf(sentences.size()), Integer.valueOf(report.candidates.size()),
                Integer.valueOf(report.unrankedCandidates), Integer.valueOf(flagged),
                Integer.valueOf(attributed), Double.valueOf(report.overallRate)));
        if (report.overallRate <= 0d || flagged == 0) {
            System.out.println("NOTE 种了真句却报零——这条链路又断了，照着 INTAKE 那几行找是哪个环节没拿到可比材料。");
            System.exit(1);
        }
    }

    private static ArrayList<String> listOf(String[] engines) {
        ArrayList<String> out = new ArrayList<String>();
        for (String engine : engines)
            if (!engine.trim().isEmpty()) out.add(engine.trim());
        return out;
    }

    /** 稿件 = 真人语料 + 均匀插进去的种句。种句落在不同段落里，才量得出"只有中段窗口问得回"的那一类。 */
    private static String manuscript(String filler, ArrayList<String> sentences) {
        ArrayList<String> paragraphs = new ArrayList<String>();
        for (String line : filler.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.length() >= 40) paragraphs.add(trimmed);
        }
        int stride = Math.max(1, paragraphs.size() / (sentences.size() + 1));
        for (int i = 0; i < sentences.size(); i++)
            paragraphs.add(Math.min(paragraphs.size(), (i + 1) * stride), sentences.get(i));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < paragraphs.size(); i++) {
            if (i > 0) out.append('\n');
            out.append(paragraphs.get(i));
        }
        return out.toString();
    }

    private static List<PaperSources.Candidate> ask(String engine, String query, PaperSources.Limits limits) {
        try {
            List<PaperSources.Candidate> out = PaperSources.search(engine, query, limits, null);
            sleep();
            return out;
        } catch (Exception error) {
            System.out.println("       " + engine + " 提问失败：" + error.getMessage());
            sleep();
            return null;
        }
    }

    /** 种子句取摘要中段那句：开头是"针对……"这类套话的位置，也是查重系统最容易蒙对的位置。 */
    private static String seedSentence(PaperSources.Candidate seed) {
        String abstractText = seed == null ? null : seed.abstractText;
        if (abstractText == null || abstractText.length() < 60) return null;
        ArrayList<int[]> ranges = TextCorpus.sentences(abstractText);
        int best = -1;
        for (int i = 0; i < ranges.size(); i++) {
            int[] range = ranges.get(i);
            if (TextCorpus.validCount(abstractText, range[0], range[1]) < MIN_SEED_VALID_CHARS) continue;
            if (best < 0) best = i;
            if (i >= ranges.size() / 2) break;
        }
        if (best < 0) return null;
        int[] range = ranges.get(best);
        String sentence = abstractText.substring(range[0], range[1]).trim();
        return sentence.indexOf('\n') >= 0 ? null : sentence;
    }

    /** 只要有一段命中与种下的句子重叠就算检出——重复区间常把上下句并进来，要求完全对齐等于换个理由判失败。 */
    private static TextCorpus.Hit covering(TextCorpus.Report report, int start, int end) {
        if (report == null) return null;
        TextCorpus.Hit best = null;
        for (TextCorpus.Hit hit : report.hits) {
            if (hit.end <= start || hit.start >= end) continue;
            if (best == null || hit.end - hit.start > best.end - best.start) best = hit;
        }
        return best;
    }

    /** 先看文献号，再退到题名归一化键：维普的服务端渲染里题名整个缺席，硬拿题名判归属等于掷硬币。 */
    private static boolean samePaper(TextCorpus.Source hit, TextCorpus.Source seed) {
        if (hit == null || seed == null) return false;
        if (!hit.id.isEmpty() && hit.id.equals(seed.id)) return true;
        if (!hit.locator.isEmpty() && hit.locator.equals(seed.locator)) return true;
        String wanted = CandidateRanker.titleKey(seed.title);
        return !wanted.isEmpty() && wanted.equals(CandidateRanker.titleKey(hit.title));
    }

    private static String filler(String path) throws Exception {
        String all = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (String line : all.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.length() < 40) continue;
            out.append(trimmed).append('\n');
            if (out.length() > 6000) break;
        }
        if (out.length() < 600) throw new IllegalStateException("filler corpus too short: " + path);
        return out.toString();
    }

    private static void sleep() {
        try { Thread.sleep(REQUEST_GAP_MILLIS); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String cut(String value, int max) {
        if (value == null || value.isEmpty()) return "-";
        String flat = value.replace('\n', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }
}
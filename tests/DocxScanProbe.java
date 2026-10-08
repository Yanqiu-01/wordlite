package com.rikkahub.wordlite;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 拿真稿子跑一遍产品链路，并把每一次对外请求的账落全（host 上跑，走真网）。
 *
 * <p>它补的是 FullScanProbe 看不到的那一半：那台自己造稿件、自己种句子，量的是"种下去抓不抓得回"；
 * 这一台直接吃 tests/samples/input-liu.docx 这种真稿，量的是"这篇稿子跑一遍，最后有几篇真正文进了比对、
 * 花了多少流量、多少时间、命中了谁"。改前改后各跑一次，报的就是同一把尺上的两个数。
 *
 * <p>用法：{@code java DocxScanProbe <file.docx|file.txt> <out-prefix> [proxy] [per-page] [windows]
 *              [fullTexts] [--cmap=文件] [--engines=a,b,c]}
 * 三个开关各管一件事：
 *   --cmap=    随包的 Adobe-GB1 表在 host/探针上没有 Context 可取，从文件装（不装就是 2.3.0 的取字水平）；
 *   --engines= 只问这几家。OpenAlex 按出口 IP 计配额（X-RateLimit-Limit: 1000，一条 0.0001，
 *              突发完就 429 并回 Retry-After: 3600），所以一轮真跑要能把配额花在哪家说清楚；
 *   .txt 稿件  手机那条路（app_process）没有 DocxParser 可用，纯文本走同一条 DuplicateEngine.scan。
 * 产出：{@code <out-prefix>.txt} 人读的一页，{@code <out-prefix>-shapes.tsv} 每一次请求/下载一行。
 * 不在闸门里（一轮几十到上百个真请求），见 {@code tools/docx-scan-probe.ps1}。
 */
public final class DocxScanProbe {
    /** 留档出口在泳道线程上被并发调用，所以自己上锁。 */
    static final ArrayList<PaperSources.ShapeRow> ROWS = new ArrayList<PaperSources.ShapeRow>();

    private DocxScanProbe() { }

    public static void main(String[] argv) throws Exception {
        String docx = argv.length > 0 ? argv[0] : "tests/samples/input-liu.docx";
        String prefix = argv.length > 1 ? argv[1] : "artifacts/tmp/docx-scan";
        String proxy = argv.length > 2 ? argv[2].trim() : "";
        int per = argv.length > 3 ? Integer.parseInt(argv[3]) : 12;
        int windows = argv.length > 4 ? Integer.parseInt(argv[4]) : 12;
        int fullTexts = argv.length > 5 ? Integer.parseInt(argv[5]) : DuplicateEngine.MAX_FULL_TEXTS;
        String cmap = flag(argv, "--cmap=");
        String engineList = flag(argv, "--engines=");
        String openAlex = flag(argv, "--openalex=");
        if (openAlex != null && !openAlex.isEmpty()) PaperSources.setEndpoint("openalex", openAlex);
        if (cmap != null && !cmap.isEmpty()) {
            FileInputStream table = new FileInputStream(cmap);
            try { CidUnicodeTables.install(table); } finally { table.close(); }
        }

        DocxDocument document;
        if (docx.toLowerCase(Locale.ROOT).endsWith(".txt") || docx.toLowerCase(Locale.ROOT).endsWith(".md")) {
            document = fromText(new String(Files.readAllBytes(Paths.get(docx)), StandardCharsets.UTF_8));
        } else {
            FileInputStream input = new FileInputStream(docx);
            try { document = DocxParser.parse(input, docx); } finally { input.close(); }
        }
        TextSelection selection = TextSelection.all(document);
        ArrayList<String> engines = engineList == null || engineList.isEmpty()
                ? PaperSources.engines() : listOf(engineList);

        final PaperSources.Limits limits = new PaperSources.Limits();
        limits.perEngine = per;
        limits.windows = windows;
        limits.fullTexts = fullTexts;
        limits.timeoutSeconds = 25;
        limits.proxy = proxy;
        limits.shapes = new PaperSources.ShapeSink() {
            public synchronized void record(PaperSources.ShapeRow row) { ROWS.add(row); }
        };

        StringBuilder log = new StringBuilder();
        long began = System.currentTimeMillis();
        DuplicateEngine.Report report = DuplicateEngine.scan(selection, new TextCorpus(), true,
                engines, limits, null, new DuplicateEngine.Progress() {
                    public void step(String label, int done, int total) {
                        System.out.println("  [" + done + "/" + total + "] " + label);
                    }
                });
        long elapsed = System.currentTimeMillis() - began;

        line(log, "docx=" + docx + " chars=" + selection.text.length()
                + " comparableChars=" + report.comparableChars);
        line(log, "limits per-page=" + per + " windows=" + windows + " fullTexts=" + fullTexts
                + " proxy=" + (proxy.isEmpty() ? "直连优先" : proxy) + " engines=" + engines);
        CidUnicodeTables table = CidUnicodeTables.active();
        if (openAlex != null && !openAlex.isEmpty())
            line(log, "openalex-endpoint " + openAlex + "（本机转发器直连上游：Routes 对海外源是代理优先，"
                    + "而这条代理出口的 OpenAlex 配额已见底、直连那条还有——见 tools/openalex-relay.py）");
        line(log, "cid-table " + (table == null ? "没装：这一版的取字水平等于 2.3.0" : table.describe())
                + "（来源 " + (cmap == null || cmap.isEmpty() ? "无" : cmap) + "）");
        line(log, "windows available=" + report.windowsAvailable + " planned=" + report.windowsPlanned
                + " retrieved=" + report.windowsRetrieved + " coveredChars=" + report.coveredChars
                + String.format(Locale.ROOT, " coverage=%.2f%%",
                        report.comparableChars <= 0 ? 0d : report.coveredChars * 100d / report.comparableChars));
        line(log, "candidates=" + report.candidates.size() + " comparable=" + report.comparableCandidates
                + " fullText=" + report.fullTextCandidates + " abstractOnly=" + report.abstractOnlyCandidates
                + " recordOnly=" + report.recordOnlyCandidates + " unranked=" + report.unrankedCandidates
                + " merged=" + report.mergedDuplicates + " localDocs=" + report.localDocuments);
        line(log, String.format(Locale.ROOT,
                "headline overall=%.2f%% excl-citations=%.2f%% self-written=%.2f%%",
                report.overallRate, report.excludingCitationsRate, report.selfWrittenRate));
        line(log, "comparedChars=" + report.comparedChars + " duplicateChars=" + report.duplicateChars
                + " citedDuplicate=" + report.citedDuplicateChars + " hits=" + report.hits.size()
                + " elapsed=" + elapsed + "ms (engine " + report.elapsedMillis + "ms)");
        line(log, "abstract layer: units=" + report.abstractUnits + " candidates=" + report.abstractCandidates
                + " compared=" + report.abstractSentencesCompared + " matched=" + report.abstractSentencesMatched
                + String.format(Locale.ROOT, " rate=%.2f%%", report.abstractLayerRate));

        int shown = 0;
        for (TextCorpus.Hit hit : report.hits) {
            if (shown++ >= 8) break;
            line(log, String.format(Locale.ROOT, "  hit [%d,%d) score=%.3f src=%s | %s", hit.start, hit.end,
                    hit.score, hit.source == null ? "-" : cut(hit.source.title, 40),
                    slice(selection.text, hit.start, hit.end)));
        }
        line(log, "入库明细（正文=抓到开放获取全文，摘要=只有摘要可比，题录=没法比对）：");
        for (PaperSources.Candidate candidate : report.candidates) {
            if (!DuplicateEngine.MATERIAL_FULL.equals(candidate.comparableMaterial)) continue;
            line(log, "  正文 " + candidate.source.engine + " | " + cut(candidate.source.title, 44)
                    + " | urlrank=" + PaperSources.pdfUrlRank(candidate.fullTextUrl)
                    + " | " + PaperSources.hostOf(candidate.fullTextUrl));
        }
        line(log, "downloadables=" + report.downloadables.size());
        for (DuplicateEngine.Downloadable pick : report.downloadables)
            line(log, "  dl " + pick.engine + " | " + cut(pick.title, 44) + " | urlrank="
                    + PaperSources.pdfUrlRank(pick.url) + " | " + pick.url);

        long pdfBytes = 0, pdfTries = 0, pdfBodies = 0, pdfChars = 0, otherBytes = 0, searchMillis = 0;
        StringBuilder tsv = new StringBuilder("engine\tstatus\tbytes\tshape\tmillis\tprobe\texcerpt\n");
        ArrayList<PaperSources.ShapeRow> rows = new ArrayList<PaperSources.ShapeRow>(report.shapes);
        for (PaperSources.ShapeRow row : rows) {
            tsv.append(row.engine).append('\t').append(row.status).append('\t').append(row.bodyBytes)
                    .append('\t').append(row.shape).append('\t').append(row.millis).append('\t')
                    .append(tidy(row.probe)).append('\t').append(tidy(row.excerpt)).append('\n');
            searchMillis += row.millis;
            boolean pdf = row.shape != null && (row.shape.startsWith("pdf-") || row.shape.startsWith("not-a-pdf"));
            if (pdf) {
                pdfTries++;
                if (row.bodyBytes > 0) pdfBytes += row.bodyBytes;
                if (row.shape.startsWith("pdf-body")) { pdfBodies++; pdfChars += charsOf(row.excerpt); }
            } else if (row.bodyBytes > 0) otherBytes += row.bodyBytes;
        }
        line(log, "traffic shape-rows=" + rows.size() + " dropped=" + report.shapesDropped
                + " 检索响应字节=" + otherBytes + " PDF字节=" + pdfBytes + " (" + (pdfBytes / 1024) + " KB)"
                + " 合计=" + (pdfBytes + otherBytes) + " (" + ((pdfBytes + otherBytes) / 1024) + " KB)");
        line(log, "pdf fetches=" + pdfTries + " 见正文=" + pdfBodies + " 抽字=" + pdfChars
                + " 网络耗时合计=" + searchMillis + "ms");
        for (String shape : tally(rows)) line(log, "  shape " + shape);
        for (String note : report.notes) line(log, "note: " + note);

        Files.createDirectories(Paths.get(prefix).getParent() == null
                ? Paths.get(".") : Paths.get(prefix).getParent());
        Files.write(Paths.get(prefix + "-shapes.tsv"), tsv.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(Paths.get(prefix + ".txt"), log.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + prefix + ".txt and " + prefix + "-shapes.tsv");
    }

    /** 纯文本稿件：一行一段，与 FullScanProbe 同样的搭法，走的还是同一条 scan 链路。 */
    static DocxDocument fromText(String text) {
        DocxDocument document = new DocxDocument();
        String[] lines = (text == null ? "" : text).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            DocxDocument.ParagraphBlock block = new DocxDocument.ParagraphBlock();
            block.index = i;
            block.text = lines[i];
            document.blocks.add(block);
            document.paragraphs.add(block);
        }
        return document;
    }

    static ArrayList<String> listOf(String engines) {
        ArrayList<String> out = new ArrayList<String>();
        for (String engine : engines.split(",")) if (engine.trim().length() > 0) out.add(engine.trim());
        return out;
    }

    static String flag(String[] argv, String name) {
        for (int i = 0; i < argv.length; i++)
            if (argv[i] != null && argv[i].startsWith(name)) return argv[i].substring(name.length()).trim();
        return null;
    }

    static int charsOf(String excerpt) {
        String value = excerpt == null ? "" : excerpt.trim();
        int sp = value.indexOf(' ');
        try { return sp < 0 ? 0 : Integer.parseInt(value.substring(0, sp)); }
        catch (NumberFormatException error) { return 0; }
    }

    /** 形状 tally：同一个形状出现几次，按第一次出现的顺序列出来。 */
    static ArrayList<String> tally(ArrayList<PaperSources.ShapeRow> rows) {
        ArrayList<String> keys = new ArrayList<String>();
        ArrayList<Integer> counts = new ArrayList<Integer>();
        for (PaperSources.ShapeRow row : rows) {
            String key = String.valueOf(row.shape);
            int at = keys.indexOf(key);
            if (at < 0) { keys.add(key); counts.add(Integer.valueOf(1)); continue; }
            counts.set(at, Integer.valueOf(counts.get(at).intValue() + 1));
        }
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < keys.size(); i++) out.add(counts.get(i) + " " + keys.get(i));
        return out;
    }
    static void line(StringBuilder log, String value) {
        log.append(value).append('\n');
        System.out.println(value);
    }
    static String tidy(String value) {
        return (value == null ? "" : value).replaceAll("\\s+", " ").trim();
    }
    static String slice(String text, int start, int end) {
        if (text == null || start < 0 || end <= start || end > text.length()) return "";
        return cut(text.substring(start, end).replace('\n', ' '), 56);
    }
    static String cut(String value, int max) {
        String flat = value == null ? "" : value.replace('\n', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }
}

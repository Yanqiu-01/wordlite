package com.rikkahub.wordlite;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 结果页「可以下进自建库的开放获取全文 N 篇」这一屏的复现台（host 上跑，走真网，不进闸门）。
 *
 * <p>它量的是屏幕上那句承诺的实物。那一屏说"另有 N 篇候选挂着可直接下载的开放获取 PDF"，
 * 真按下去回执只有"导入 0 篇，失败 N 个"。这一台把同一批链接按 app 自己的取法逐篇走两遍：
 * 先 {@link PaperSources#fetchPdf} 看链接回来的到底是什么形状（状态码 / 字节 / 解出的字数），
 * 再原样跑一遍 {@link CorpusImport#download}，把屏幕会印的 summary() 与 detail() 原文打出来。
 *
 * <p>用法：{@code java -cp <classes> com.rikkahub.wordlite.DownloadableProbe
 * <file.docx> <out-prefix> [proxy] [per-page] [windows]}
 * 参数与 app 里那次查重对齐：per-page=12、windows=12、fullTexts=MAX_FULL_TEXTS、timeout=20。
 * 产出 {@code <out-prefix>.txt}（人读一页）与 {@code <out-prefix>-items.tsv}（一链接一行）。
 */
public final class DownloadableProbe {

    public static void main(String[] argv) throws Exception {
        String docx = argv.length > 0 ? argv[0] : "tests/samples/input-liu.docx";
        String prefix = argv.length > 1 ? argv[1] : "artifacts/tmp/downloadable";
        String proxy = argv.length > 2 ? argv[2].trim() : "";
        int per = argv.length > 3 ? Integer.parseInt(argv[3]) : 12;
        int windows = argv.length > 4 ? Integer.parseInt(argv[4]) : 12;
        boolean skipScan = argv.length > 5 && argv[5].equals("--resume");

        StringBuilder log = new StringBuilder();
        ArrayList<DuplicateEngine.Downloadable> picks = new ArrayList<DuplicateEngine.Downloadable>();
        PaperSources.Limits limits = new PaperSources.Limits();
        limits.perEngine = per;
        limits.windows = windows;
        limits.fullTexts = DuplicateEngine.MAX_FULL_TEXTS;
        limits.timeoutSeconds = 20;
        limits.proxy = proxy;

        if (!skipScan) {
            DocxDocument document;
            FileInputStream input = new FileInputStream(docx);
            try { document = DocxParser.parse(input, docx); } finally { input.close(); }
            TextSelection selection = TextSelection.all(document);
            long began = System.currentTimeMillis();
            DuplicateEngine.Report report = DuplicateEngine.scan(selection, new TextCorpus(), true,
                    PaperSources.engines(), limits, null, null);
            line(log, "docx=" + docx + " chars=" + selection.text.length()
                    + " comparableChars=" + report.comparableChars);
            line(log, "limits per-page=" + per + " windows=" + windows
                    + " fullTexts=" + limits.fullTexts + " proxy="
                    + (proxy.isEmpty() ? "直连优先" : proxy));
            line(log, "候选=" + report.candidates.size() + " 入库=" + report.comparableCandidates
                    + " 抓到正文=" + report.fullTextCandidates + " 只有摘要=" + report.abstractOnlyCandidates
                    + " 只有题录=" + report.recordOnlyCandidates + " 可比正文=" + report.comparableCandidates
                    + " 用时=" + (System.currentTimeMillis() - began) + "ms");
            line(log, "那一屏列出的候选（downloadables）=" + report.downloadables.size());
            for (String note : report.notes) line(log, "note: " + note);
            picks.addAll(report.downloadables);
        } else {
            for (File file : new File(docx).listFiles()) {
                if (!file.getName().endsWith(".url")) continue;
                String body = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                String[] parts = body.split("\n", 3);
                picks.add(new DuplicateEngine.Downloadable(parts.length > 2 ? parts[2] : file.getName(),
                        parts.length > 1 ? parts[1] : "", parts.length > 0 ? parts[0] : ""));
            }
            line(log, "resume：从 " + docx + " 读回 " + picks.size() + " 条链接");
        }

        /* 逐篇：链接自己说什么，就报什么，不替它圆场。 */
        StringBuilder tsv = new StringBuilder("no\tengine\trank\tpdfLink\thost\tstatus\tbytes\tchars\tshape\terror\ttitle\turl\n");
        int usable = 0;
        for (int i = 0; i < picks.size(); i++) {
            DuplicateEngine.Downloadable pick = picks.get(i);
            PaperSources.PdfFetch got = PaperSources.fetchPdf(pick.url, limits, null);
            boolean ok = got.chars > 0;
            if (ok) usable++;
            line(log, "DL #" + (i + 1) + " " + pick.engine + " rank=" + PaperSources.pdfUrlRank(pick.url)
                    + " pdfLink=" + PaperSources.pdfLink(pick.url) + " status=" + got.status
                    + " bytes=" + got.bytes.length + " chars=" + got.chars + " pages=" + got.pages
                    + " shape=" + got.shape + (got.error.isEmpty() ? "" : " | " + got.error));
            line(log, "     " + cut(pick.title, 54) + " | " + PaperSources.hostOf(pick.url));
            tsv.append(i + 1).append('\t').append(pick.engine).append('\t')
                    .append(PaperSources.pdfUrlRank(pick.url)).append('\t')
                    .append(PaperSources.pdfLink(pick.url)).append('\t')
                    .append(PaperSources.hostOf(pick.url)).append('\t').append(got.status).append('\t')
                    .append(got.bytes.length).append('\t').append(got.chars).append('\t')
                    .append(got.shape).append('\t').append(tidy(got.error)).append('\t')
                    .append(tidy(pick.title)).append('\t').append(pick.url).append('\n');
        }
        line(log, "DOWNLOADABLE 那一屏 " + picks.size() + " 篇，链接回来能解出字的 " + usable + " 篇");

        /* 再按屏上那一步原样走一遍：下载 + 入库，回执取屏幕会显示的原文。 */
        File libraryDir = new File(prefix + "-library");
        libraryDir.mkdirs();
        ArrayList<CorpusImport.Pick> items = new ArrayList<CorpusImport.Pick>();
        for (int i = 0; i < picks.size(); i++)
            items.add(new CorpusImport.Pick(picks.get(i).title, picks.get(i).url));
        CorpusImport.Batch batch = CorpusImport.download(new LocalLibrary(libraryDir), items,
                new CorpusImport.Fetch() {
                    public byte[] get(String url) throws Exception {
                        return PaperSources.downloadPdf(url, limits, null);
                    }
                }, null, null);
        line(log, "屏上标题行 summary(): " + batch.summary());
        line(log, "屏上逐篇回执 detail() 原文:");
        for (String row : batch.detail().split("\n")) line(log, "  " + row);
        line(log, "进了库的文件数=" + new LocalLibrary(libraryDir).entries().size());

        if (Paths.get(prefix).getParent() != null) Files.createDirectories(Paths.get(prefix).getParent());
        Files.write(Paths.get(prefix + ".txt"), log.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(Paths.get(prefix + "-items.tsv"), tsv.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + prefix + ".txt and " + prefix + "-items.tsv");
    }

    static void line(StringBuilder log, String value) {
        log.append(value).append('\n');
        System.out.println(value);
    }

    static String cut(String value, int max) {
        String flat = value == null ? "" : value.replace('\n', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }

    static String tidy(String value) {
        return (value == null ? "" : value).replaceAll("\\s+", " ").trim();
    }
}
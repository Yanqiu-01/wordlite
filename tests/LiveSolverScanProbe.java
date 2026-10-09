package com.rikkahub.wordlite;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 活体全流程探针：真过验证服务 + 真代理 + 真检索源，跑生产入口 DuplicateEngine.scan，
 * 把总相似度比与每一笔出处打出来。不进 test-host（要外部服务），手动跑：
 *
 *   java -classpath <host classpath> com.rikkahub.wordlite.LiveSolverScanProbe \
 *       <稿件.txt> <http://过验证服务> <http://代理> <wanfang,cqvip,cnki>
 *
 * 稿件用空行分段，第一段按标题处理（不参与检索窗口）。
 */
public final class LiveSolverScanProbe {
    public static void main(String[] args) throws Exception {
        String file = args.length > 0 ? args[0] : "artifacts/_scratch/live/manuscript.txt";
        String solver = args.length > 1 ? args[1] : "http://127.0.0.1:8191";
        String proxy = args.length > 2 ? args[2] : "127.0.0.1:7897";
        String enginesCsv = args.length > 3 ? args[3] : "wanfang,cqvip,cnki";
        String raw = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
        DocxDocument document = new DocxDocument();
        int index = 0;
        for (String one : raw.split("\r?\n\s*\r?\n")) {
            String text = one.trim();
            if (text.length() == 0) continue;
            DocxDocument.ParagraphBlock block = new DocxDocument.ParagraphBlock();
            block.index = index++;
            block.text = text.replaceAll("\s*\n\s*", "");
            document.blocks.add(block);
            document.paragraphs.add(block);
        }
        PaperSources.Limits limits = new PaperSources.Limits();
        limits.solver = solver;
        limits.proxy = proxy;
        limits.timeoutSeconds = 25;
        limits.perEngine = 8;
        limits.windows = 3;
        limits.fullTexts = 2;
        ArrayList<String> engines = new ArrayList<String>(Arrays.asList(enginesCsv.split(",")));
        System.out.println("稿件 " + document.paragraphs.size() + " 段；引擎 " + engines
                + "；过验证 " + solver + "；代理 " + proxy);
        DuplicateEngine.Report report = DuplicateEngine.scan(TextSelection.all(document), new TextCorpus(),
                true, engines, limits, new ApiClient.Task(), null);
        System.out.println("---- 结果 ----");
        System.out.println("总相似度比 " + percent(report.overallRate) + "（比对 " + report.comparedChars
                + " 字，重复 " + report.duplicateChars + " 字）；去引用 "
                + percent(report.excludingCitationsRate) + "；自编 " + percent(report.selfWrittenRate));
        System.out.println("按来源：" + percent(report.localRate) + " 自建库 / " + percent(report.webRate)
                + " 联网；按材料档：正文 " + report.fullTextDuplicateChars + " 字 / 全文摘要 "
                + report.digestDuplicateChars + " 字 / 检索摘要 " + report.abstractDuplicateChars
                + " 字（命中篇数 " + report.fullTextHitPapers + "/" + report.digestHitPapers + "/"
                + report.abstractHitPapers + "）");
        System.out.println("摘要层：" + DuplicateEngine.abstractLayerLine(report) + "；AIGC 倾向 "
                + percent(report.aigcRate) + " " + report.aigcVerdict);
        System.out.println("候选：" + report.candidateCount + " 入库来源 " + (report.inventory == null ? "?" : report.inventory.summaryLine()) + " ；浏览器喂进正文 " + report.autoBodies.size() + " 篇");
        System.out.println("命中区间 " + report.hits.size() + " 段：");
        LinkedHashMap<String, Integer> perSource = new LinkedHashMap<String, Integer>();
        for (TextCorpus.Hit hit : report.hits) {
            String key = (hit.source == null ? "?" : hit.source.engine) + " | "
                    + (hit.source == null ? "?" : shorten(hit.source.title)) + " ["
                    + (hit.source == null ? "?" : hit.source.material) + "]";
            Integer old = perSource.get(key);
            perSource.put(key, Integer.valueOf((old == null ? 0 : old.intValue()) + (hit.end - hit.start)));
        }
        for (Map.Entry<String, Integer> entry : perSource.entrySet())
            System.out.println("  " + entry.getValue().intValue() + " 字  " + entry.getKey());
        System.out.println("注记：");
        for (String one : report.notes) System.out.println("  - " + one);
        System.out.println("耗时 " + report.elapsedMillis + "ms；检索未完成=" + report.retrievalIncomplete
                + " " + report.retrievalReason);
    }

    static String percent(double rate) {
        return String.format(Locale.ROOT, "%.2f%%", Double.valueOf(rate));
    }

    static String shorten(String value) {
        String text = value == null ? "" : value.trim();
        return text.length() <= 42 ? text : text.substring(0, 42) + "...";
    }
}

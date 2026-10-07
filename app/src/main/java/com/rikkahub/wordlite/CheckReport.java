package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

/** Local standalone report, never fetches or embeds remote content. */
public final class CheckReport {
    private CheckReport() { }
    public static String html(String fileName, ApiResult.Check result) {
        StringBuilder out = new StringBuilder("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">");
        out.append("<title>查重报告</title><style>body{font:16px sans-serif;max-width:900px;margin:32px auto;padding:16px;color:#202020}h1{font-size:24px}td,th{border:1px solid #ccc;padding:10px;text-align:left;white-space:pre-wrap;word-break:break-word}table{border-collapse:collapse;width:100%}</style><h1>查重报告</h1><p>")
                .append(escape(fileName)).append("</p><p>重复率：").append(String.format(Locale.US, "%.2f%%", result.rate))
                .append("</p><p>检测时间：").append(escape(result.detectedAt)).append("</p><table><thead><tr><th>相似片段</th><th>相似来源</th></tr></thead><tbody>");
        for (ApiResult.Fragment fragment : result.fragments)
            out.append("<tr><td>").append(escape(fragment.text)).append("</td><td>").append(escape(fragment.source)).append("</td></tr>");
        return out.append("</tbody></table></html>").toString();
    }
    public static String html(String fileName, DuplicateEngine.Report report) {
        StringBuilder out = new StringBuilder("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">");
        out.append("<title>查重与 AIGC 报告</title>").append(REPORT_STYLE).append("<h1>查重与 AIGC 报告</h1><p>")
                .append(escape(fileName)).append("</p><p>检测时间：").append(escape(report.detectedAt))
                .append(" &#183; 耗时 ").append(report.elapsedMillis).append(" ms &#183; 相似片段 ")
                .append(report.hits.size()).append(" 处</p>");
        if (report.retrievalIncomplete) unfinished(out, report); else completed(out, report);
        /* 未完成也交代问了几个窗口：覆盖率小节在两种头部之后都调。 */
        coverage(out, report);
        merges(out, report);
        engines(out, report);
        sourcesLedger(out, report);
        candidates(out, report);
        snippets(out, report);
        aigc(out, report);
        out.append("<h2>检测说明</h2>");
        if (report.notes.isEmpty()) out.append("<p>无附加说明。</p>");
        else {
            out.append("<ul>");
            for (String note : report.notes) out.append("<li>").append(escape(note)).append("</li>");
            out.append("</ul>");
        }
        return out.append("</html>").toString();
    }
    /** The three duplication rates: only meaningful once something was actually consulted. */
    private static void completed(StringBuilder out, DuplicateEngine.Report report) {
        out.append("<h2>指标</h2><table><thead><tr><th>指标</th><th>比例</th></tr></thead><tbody>");
        metric(out, "总相似度比", report.overallRate);
        metric(out, "去除引用重复比", report.excludingCitationsRate);
        metric(out, "自编率", report.selfWrittenRate);
        aigcMetric(out, report);
        out.append("</tbody></table><p>参与比对 ").append(report.comparedChars)
                .append(" 个有效字符（语料侧可比候选 ").append(report.comparableCandidates).append(" 篇），命中相似 ")
                .append(report.duplicateChars).append(" 个，其中落在引用区间内 ").append(report.citedDuplicateChars).append(" 个。</p>");
    }
    /** A run that consulted nothing gets 未完成查重 as its headline; the AIGC share is local, so it stays. */
    private static void unfinished(StringBuilder out, DuplicateEngine.Report report) {
        String reason = report.retrievalReason == null ? "" : report.retrievalReason.trim();
        out.append("<h2>未完成查重</h2><p><strong>")
                .append(escape(reason.isEmpty() ? "本次没有可比对的文献来源" : reason)).append("</strong></p>")
                .append("<p>本次没有取回任何可比对的文献，相似度类指标无法成立，只有 AIGC 倾向是本机计算的结果。</p>");
        out.append("<h2>指标</h2><table><thead><tr><th>指标</th><th>比例</th></tr></thead><tbody>");
        aigcMetric(out, report);
        out.append("</tbody></table>");
    }
    /**
     * 检索覆盖率：这次到底看了论文的多少。部分覆盖必须把「相似率是下限」写在同一节里，
     * 否则读者会把 12% 当成上限而不是下限。
     */
    private static void coverage(StringBuilder out, DuplicateEngine.Report report) {
        if (report == null) return;
        if (report.windowsPlanned <= 0 && report.comparableCandidates <= 0) return;
        double rate = report.comparableChars <= 0 ? 0 : report.coveredChars * 100d / report.comparableChars;
        out.append("<h2>检索覆盖率</h2><table><thead><tr><th>项目</th><th>数值</th></tr></thead><tbody>");
        metricText(out, "已检索窗口数", report.windowsRetrieved + "/" + report.windowsAvailable + " 个窗口组"
                + "（本次设置允许 " + report.windowsPlanned + " 个）");
        metricText(out, "已覆盖字数", report.coveredChars + " 字（可检索正文 " + report.comparableChars + " 字）");
        metricText(out, "覆盖率", percent(rate));
        metricText(out, "可比候选文献", report.comparableCandidates + " 篇（仅摘要可比 "
                + report.abstractOnlyCandidates + " 篇，只有题录 " + report.recordOnlyCandidates + " 篇）");
        metricText(out, "开放获取全文", report.fullTextCandidates + " 篇已抓取；与检索词零共同词被挡掉 "
                + report.unrankedCandidates + " 篇");
        out.append("</tbody></table>");
        if (report.retrievalPartial) {
            String reason = report.retrievalPartialReason == null ? "" : report.retrievalPartialReason.trim();
            out.append("<p><strong>").append(escape(reason.isEmpty() ? "本次未检索完全，相似率是下限" : reason))
                    .append("</strong></p>");
        }
    }
    /** 跨源合并的账目：留下谁、并掉谁、按哪个键并的、凭什么留它。 */
    private static void merges(StringBuilder out, DuplicateEngine.Report report) {
        if (report == null || report.merges.isEmpty()) return;
        out.append("<h2>跨源合并 ").append(report.mergedDuplicates).append(" 篇</h2>")
                .append("<table><thead><tr><th>合并键</th><th>留下的文献</th><th>被并入的文献</th><th>理由</th></tr></thead><tbody>");
        for (CandidateRanker.Merged merged : report.merges) {
            String via = "doi".equals(merged.keyKind) ? "按 DOI " + merged.key : "按标题指纹 " + merged.key;
            out.append("<tr><td>").append(escape(via)).append("</td><td>").append(escape(describe(merged.kept)))
                    .append("</td><td>").append(escape(describe(merged.dropped))).append("</td><td>")
                    .append(escape(merged.reason)).append("</td></tr>");
        }
        out.append("</tbody></table>");
    }
    private static String describe(PaperSources.Candidate candidate) {
        if (candidate == null || candidate.source == null) return "空记录";
        return PaperSources.label(candidate.source.engine) + "《" + candidate.source.title + "》";
    }
    private static void engines(StringBuilder out, DuplicateEngine.Report report) {
        ArrayList<String> names = new ArrayList<String>();
        for (String name : report.byEngine.keySet()) if (!names.contains(name)) names.add(name);
        for (String name : report.candidateCount.keySet()) if (!names.contains(name)) names.add(name);
        out.append("<h2>按检索源分布</h2>");
        if (names.isEmpty()) { out.append("<p>本次检测没有来源分布。</p>"); return; }
        out.append("<table><thead><tr><th>检索源</th><th>重复字符占比</th><th>候选文献数</th>")
                .append("<th>提问窗口数</th></tr></thead><tbody>");
        for (String name : names) {
            Double share = report.byEngine.get(name);
            Integer count = report.candidateCount.get(name);
            Integer asked = report.windowsAsked.get(name);
            out.append("<tr><td>").append(escape(PaperSources.label(name))).append("</td><td>")
                    .append(percent(share == null ? 0 : share.doubleValue())).append("</td><td>")
                    .append(count == null ? 0 : count.intValue()).append("</td><td>")
                    .append(asked == null ? 0 : asked.intValue()).append("</td></tr>");
        }
        out.append("</tbody></table>");
    }
    /**
     * 来源榜：命中按文献聚合。排在"按检索源分布"与"候选文献"之间——读报告的顺序是
     * 总量 → 哪个检索源命中最多 → 哪一篇 → 哪一段，来源榜补的是"哪一篇"这一级。
     * 账本从 report.hits 现场反推（SourceLedger 是纯函数），DuplicateEngine.Report 不必为它加字段。
     */
    private static void sourcesLedger(StringBuilder out, DuplicateEngine.Report report) {
        if (report == null || report.sourceText == null || report.sourceText.isEmpty()) return;
        SourceLedger ledger = SourceLedger.aggregate(report.hits, TextCorpus.normalize(report.sourceText),
                report.comparedChars);
        if (ledger.rows.isEmpty()) {
            // 什么都没比对成：头部已经是"未完成查重"，再排一张空来源榜会被读成"这篇很干净"。
            if (report.retrievalIncomplete) return;
            ledgerNoHit(out, report);
            return;
        }
        out.append("<h2>来源榜 &#183; 按文献 &#183; 共 ").append(ledger.paperCount).append(" 篇命中</h2>");
        out.append("<table><thead><tr><th>#</th><th>文献</th><th>检索源</th><th>重复字符</th>")
                .append("<th>该篇重复率</th><th>处数</th><th>标识符</th></tr></thead><tbody>");
        for (int i = 0; i < ledger.rows.size(); i++) {
            SourceLedger.Row row = ledger.rows.get(i);
            out.append("<tr><td>").append(row.others ? "&#8212;" : String.valueOf(i + 1)).append("</td><td>");
            if (row.others) {
                out.append(escape("其余 " + row.othersCount + " 篇合计"));
            } else {
                out.append(escape(row.title));
                String meta = ledgerMeta(row);
                if (meta.length() > 0) out.append("<br><span>").append(meta).append("</span>");
            }
            out.append("</td><td>").append(escape(row.engine)).append("</td><td>").append(row.duplicateChars)
                    .append("</td><td>").append(percent(row.share(ledger.comparedChars)))
                    .append("</td><td>").append(row.hitCount).append("</td><td>");
            ledgerIdentifier(out, row);
            out.append("</td></tr>");
        }
        out.append("</tbody></table>");
        out.append("<p>各篇重复率用整篇有效字数 ").append(ledger.comparedChars)
                .append(" 个有效字符当同分母，所以各行相加就是总相似度比；同一段字符只记给命中最长的那一篇。</p>");
        for (int i = 0; i < LEDGER_SAMPLE_ROWS && i < ledger.rows.size(); i++)
            ledgerSample(out, report.sourceText, ledger.rows.get(i), i + 1);
    }

    /** 比对过但一篇都没命中：把"未命中不等于全文没有重复"写死，别留一张空表让人自己脑补。 */
    private static void ledgerNoHit(StringBuilder out, DuplicateEngine.Report report) {
        int consulted = consultedCount(report);
        out.append("<h2>来源榜</h2>");
        if (consulted <= 0) {
            out.append("<p>没有可比对文献命中，也没有来源榜可排。未命中不等于全文没有重复。</p>");
            return;
        }
        out.append("<p>比对过 ").append(consulted).append(" 篇候选与自建库，没有一篇命中相似片段。未命中只说明这 ")
                .append(consulted).append(" 篇里没有相似段落，未命中不等于全文没有重复。</p>");
    }

    /** 作者 &#183; 年份 &#183; 并自几条题录：并了两条题录这件事要在表里露出来，否则用户会以为库里真有两篇。 */
    private static String ledgerMeta(SourceLedger.Row row) {
        StringBuilder meta = new StringBuilder();
        if (!row.authors.isEmpty()) meta.append(escape(row.authors));
        if (!row.year.isEmpty()) {
            if (meta.length() > 0) meta.append(" &#183; ");
            meta.append(escape(row.year));
        }
        if (row.sourceCount > 1) {
            if (meta.length() > 0) meta.append(" &#183; ");
            meta.append(escape("并自 " + row.sourceCount + " 条题录"));
        }
        return meta.toString();
    }

    /** 标识符：只有字面以 https:// 开头的 locator 才配 <a href>。PMID:123、local:x.docx 这类一律纯文本。 */
    private static void ledgerIdentifier(StringBuilder out, SourceLedger.Row row) {
        String locator = row.locator == null ? "" : row.locator.trim();
        String text = row.doi.isEmpty() ? locator : row.doi;
        if (text.isEmpty()) return;
        if (locator.startsWith("https://"))
            out.append("<a href=\"").append(escape(locator)).append("\" rel=\"noopener\">")
                    .append(escape(text)).append("</a>");
        else out.append(escape(text));
    }

    /** 命中样例：该篇最长那一处用 <mark> 包住，左右各带 24 字上下文。只画前几行，导出报告的体积得有上限。 */
    private static void ledgerSample(StringBuilder out, String text, SourceLedger.Row row, int index) {
        if (row.others || row.longestEnd <= row.longestStart) return;
        int from = Math.max(0, row.longestStart - LEDGER_SAMPLE_CONTEXT);
        int to = Math.min(text.length(), row.longestEnd + LEDGER_SAMPLE_CONTEXT);
        out.append("<details><summary>").append(escape("第 " + index + " 篇的命中样例")).append("</summary><p>");
        if (from < row.longestStart) out.append("\u2026").append(escape(snippet(text, from, row.longestStart)));
        out.append("<mark>").append(escape(snippet(text, row.longestStart, row.longestEnd))).append("</mark>");
        if (row.longestEnd < to) {
            out.append(escape(snippet(text, row.longestEnd, to)));
            if (to < text.length()) out.append("\u2026");
        }
        out.append("</p></details>");
    }

    /** 比对过几篇题录：自建库加检索回来的候选。拿不到语料就退回候选篇数，宁可少说一个数。 */
    private static int consultedCount(DuplicateEngine.Report report) {
        int candidates = report.candidates == null ? 0 : report.candidates.size();
        if (report.baseline == null) return candidates;
        return Math.max(report.baseline.sourceCount(), candidates);
    }

    private static void candidates(StringBuilder out, DuplicateEngine.Report report) {
        out.append("<h2>候选文献 ").append(report.candidates.size()).append(" 篇</h2>");
        if (report.candidates.isEmpty()) { out.append("<p>本次检测没有取回候选文献。</p>"); return; }
        out.append("<table><thead><tr><th>检索源</th><th>标题</th><th>作者</th><th>年份</th><th>标识符</th>")
                .append("<th>可比材料</th></tr></thead><tbody>");
        for (PaperSources.Candidate candidate : report.candidates) {
            TextCorpus.Source source = candidate.source == null ? new TextCorpus.Source() : candidate.source;
            String material = candidate.comparableMaterial == null ? "" : candidate.comparableMaterial;
            out.append("<tr><td>").append(escape(PaperSources.label(source.engine))).append("</td><td>")
                    .append(escape(source.title)).append("</td><td>").append(escape(source.authors)).append("</td><td>")
                    .append(escape(source.year)).append("</td><td>").append(escape(source.locator)).append("</td><td>")
                    .append(escape(material.isEmpty() ? "未记" : material)).append("</td></tr>");
        }
        out.append("</tbody></table>");
    }
    private static void snippets(StringBuilder out, DuplicateEngine.Report report) {
        out.append("<h2>相似片段 ").append(report.hits.size()).append(" 处</h2>");
        if (report.hits.isEmpty()) { out.append("<p>未命中相似片段。</p>"); return; }
        out.append("<table><thead><tr><th>相似片段</th><th>来源标题</th><th>年份</th><th>locator</th><th>相似度</th></tr></thead><tbody>");
        for (TextCorpus.Hit hit : report.hits) {
            TextCorpus.Source source = hit.source == null ? new TextCorpus.Source() : hit.source;
            out.append("<tr><td>").append(escape(snippet(report.sourceText, hit.start, hit.end))).append("</td><td>")
                    .append(escape(source.title)).append("</td><td>").append(escape(source.year)).append("</td><td>")
                    .append(escape(source.locator)).append("</td><td>").append(percent(hit.score * 100)).append("</td></tr>");
        }
        out.append("</tbody></table>");
    }
    private static void aigc(StringBuilder out, DuplicateEngine.Report report) {
        out.append("<h2>AIGC 倾向句</h2>");
        if (report.aigc != null && report.aigc.verdict != null && report.aigc.verdict.length() > 0)
            out.append("<p>").append(escape(report.aigc.verdict)).append("</p>");
        AigcDetector.Result aigc = report.aigc;
        if (aigc == null || aigc.sentences.isEmpty()) { out.append("<p>未标记出高倾向句子。</p>"); return; }
        out.append("<table><thead><tr><th>句子</th><th>倾向分</th><th>判定依据</th></tr></thead><tbody>");
        for (AigcDetector.Sentence sentence : aigc.sentences) {
            StringBuilder features = new StringBuilder();
            for (String feature : sentence.features) {
                if (features.length() > 0) features.append("&#32178;");
                features.append(escape(feature));
            }
            out.append("<tr><td>").append(escape(snippet(report.sourceText, sentence.start, sentence.end))).append("</td><td>")
                    .append(percent(sentence.score * 100)).append("</td><td>").append(features).append("</td></tr>");
        }
        out.append("</tbody></table>");
    }
    /** 样本不足就不给百分比，这一格直接写清楚。 */
    private static void aigcMetric(StringBuilder out, DuplicateEngine.Report report) {
        if (report.aigcInsufficient) metricText(out, "AIGC 生成比例", "样本不足");
        else metric(out, "AIGC 生成比例", report.aigcRate);
    }
    private static void metricText(StringBuilder out, String name, String value) {
        out.append("<tr><td>").append(escape(name)).append("</td><td>").append(escape(value)).append("</td></tr>");
    }
    private static void metric(StringBuilder out, String name, double value) {
        metricText(out, name, percent(value));
    }
    private static String percent(double value) {
        double safe = Double.isNaN(value) || Double.isInfinite(value) ? 0 : value;
        return String.format(Locale.US, "%.2f%%", safe < 0 ? 0 : safe > 100 ? 100 : safe);
    }
    private static String snippet(String text, int start, int end) {
        if (text == null || text.isEmpty()) return "";
        int lo = start < 0 ? 0 : Math.min(start, text.length());
        int hi = end <= lo ? lo : Math.min(end, text.length());
        String value = text.substring(lo, hi).trim();
        return value.length() > 240 ? value.substring(0, 240) + "\u2026" : value;
    }
    /** 来源榜只为前几行画命中样例，导出成 HTML 文件的报告体积得有上限。 */
    private static final int LEDGER_SAMPLE_ROWS = 6;
    private static final int LEDGER_SAMPLE_CONTEXT = 24;
    private static final String REPORT_STYLE = "<style>body{font:16px sans-serif;max-width:960px;margin:32px auto;padding:16px;"
            + "color:#202020}h1{font-size:24px}h2{font-size:18px;margin:24px 0 8px}table{border-collapse:collapse;width:100%}"
            + "td,th{border:1px solid #ccc;padding:8px;text-align:left;white-space:pre-wrap;word-break:break-word}"
            + "th{background:#f2f2f2}ul{padding-left:22px}</style>";
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}

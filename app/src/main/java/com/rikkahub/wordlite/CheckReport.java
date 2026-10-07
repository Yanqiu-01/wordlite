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
        out.append("<h2>指标</h2><table><thead><tr><th>指标</th><th>比例</th></tr></thead><tbody>");
        metric(out, "总相似度比", report.overallRate);
        metric(out, "去除引用重复比", report.excludingCitationsRate);
        metric(out, "自编率", report.selfWrittenRate);
        metric(out, "AIGC 生成比例", report.aigcRate);
        out.append("</tbody></table><p>参与比对 ").append(report.comparedChars).append(" 个有效字符，命中相似 ")
                .append(report.duplicateChars).append(" 个，其中落在引用区间内 ").append(report.citedDuplicateChars).append(" 个。</p>");
        engines(out, report);
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
    private static void engines(StringBuilder out, DuplicateEngine.Report report) {
        ArrayList<String> names = new ArrayList<String>();
        for (String name : report.byEngine.keySet()) if (!names.contains(name)) names.add(name);
        for (String name : report.candidateCount.keySet()) if (!names.contains(name)) names.add(name);
        out.append("<h2>按检索源分布</h2>");
        if (names.isEmpty()) { out.append("<p>本次检测没有来源分布。</p>"); return; }
        out.append("<table><thead><tr><th>检索源</th><th>重复字符占比</th><th>候选文献数</th></tr></thead><tbody>");
        for (String name : names) {
            Double share = report.byEngine.get(name);
            Integer count = report.candidateCount.get(name);
            out.append("<tr><td>").append(escape(PaperSources.label(name))).append("</td><td>")
                    .append(percent(share == null ? 0 : share.doubleValue())).append("</td><td>")
                    .append(count == null ? 0 : count.intValue()).append("</td></tr>");
        }
        out.append("</tbody></table>");
    }
    private static void candidates(StringBuilder out, DuplicateEngine.Report report) {
        out.append("<h2>候选文献 ").append(report.candidates.size()).append(" 篇</h2>");
        if (report.candidates.isEmpty()) { out.append("<p>本次检测没有取回候选文献。</p>"); return; }
        out.append("<table><thead><tr><th>检索源</th><th>标题</th><th>作者</th><th>年份</th><th>标识符</th></tr></thead><tbody>");
        for (PaperSources.Candidate candidate : report.candidates) {
            TextCorpus.Source source = candidate.source == null ? new TextCorpus.Source() : candidate.source;
            out.append("<tr><td>").append(escape(PaperSources.label(source.engine))).append("</td><td>")
                    .append(escape(source.title)).append("</td><td>").append(escape(source.authors)).append("</td><td>")
                    .append(escape(source.year)).append("</td><td>").append(escape(source.locator)).append("</td></tr>");
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
    private static void metric(StringBuilder out, String name, double value) {
        out.append("<tr><td>").append(escape(name)).append("</td><td>").append(percent(value)).append("</td></tr>");
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
    private static final String REPORT_STYLE = "<style>body{font:16px sans-serif;max-width:960px;margin:32px auto;padding:16px;"
            + "color:#202020}h1{font-size:24px}h2{font-size:18px;margin:24px 0 8px}table{border-collapse:collapse;width:100%}"
            + "td,th{border:1px solid #ccc;padding:8px;text-align:left;white-space:pre-wrap;word-break:break-word}"
            + "th{background:#f2f2f2}ul{padding-left:22px}</style>";
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}

package com.rikkahub.wordlite;

import java.util.Locale;

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
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}

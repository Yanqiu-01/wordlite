package com.rikkahub.wordlite;

import java.util.ArrayList;

/**
 * 一台只问一件事的量台：这几条全文链接跑一遍产品那条 HTML 取字的路，每页取回多少字、
 * 响应多大、什么形状。它是为 2026-10-09 那笔账配的——opticsjournal.net 对三个不同文章链接
 * 回的是同一份人机验证页，改前每一页都能"抽出"5,850 字当正文，改后每一页判成 text-blocked。
 *
 * <p>用法：{@code java BodyShapeProbe [url ...]}（不带参数就用当天量过那三条）。
 * 走的是 PaperSources.fullText，和 app 里"下进自建库/抓全文"同一个入口，不是另写一套解析。
 * 只在 host 上手动跑，不在回归闸门里（它要打真网）。
 */
public final class BodyShapeProbe {
    private static final String[] MEASURED = {
            "https://www.opticsjournal.net/OJd2f2d669decbc75",
            "https://www.opticsjournal.net/OJ6ce2d84567669b3b",
            "https://www.opticsjournal.net/OJ4358d7e12d46c846",
    };

    public static void main(String[] args) throws Exception {
        String[] urls = args.length > 0 ? args : MEASURED;
        PaperSources.Limits limits = new PaperSources.Limits();
        limits.perEngine = 5;
        limits.timeoutSeconds = 20;
        final ArrayList<PaperSources.ShapeRow> rows = new ArrayList<PaperSources.ShapeRow>();
        limits.shapes = new PaperSources.ShapeSink() {
            public synchronized void record(PaperSources.ShapeRow row) { rows.add(row); }
        };
        int chars = 0;
        long bytes = 0L, began = System.currentTimeMillis();
        for (String url : urls) {
            PaperSources.Candidate candidate = new PaperSources.Candidate();
            candidate.source.engine = "openalex";
            candidate.fullTextUrl = url;
            String text = PaperSources.fullText(candidate, limits, null);
            PaperSources.ShapeRow row = rows.get(rows.size() - 1);
            chars += text.trim().length();
            bytes += Math.max(0, row.bodyBytes);
            System.out.println("  " + url + " -> 取回正文 " + text.trim().length() + " 字 / shape=" + row.shape
                    + " / 响应 " + row.bodyBytes + " B / " + row.millis + "ms / " + row.excerpt
                    + (row.error.isEmpty() ? "" : " / " + row.error));
        }
        System.out.println("TOTAL urls=" + urls.length + " 取回正文合计=" + chars + " 字 留档行数=" + rows.size()
                + " 响应字节=" + bytes + " 耗时=" + (System.currentTimeMillis() - began) + "ms");
    }
}

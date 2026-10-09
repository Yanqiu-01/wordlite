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
        inventory(out, report);
        merges(out, report);
        engines(out, report);
        sourcesLedger(out, report);
        candidates(out, report);
        shapes(out, report);
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
    /**
     * The three duplication rates: only meaningful once something was actually consulted.
     * 列名 0.7.1 从"比例"改成"数值"：这张表里还挂着机器生成倾向那一格，它不是比例，
     * 摆在"比例"这一列下面就会被当成第四个比率读。三个比率的名字自己已经把"比/率"写明白了。
     */
    private static void completed(StringBuilder out, DuplicateEngine.Report report) {
        out.append("<h2>指标</h2><table><thead><tr><th>指标</th><th>数值</th></tr></thead><tbody>");
        metric(out, "总相似度比", report.overallRate);
        metric(out, "去除引用重复比", report.excludingCitationsRate);
        metric(out, "自编率", report.selfWrittenRate);
        aigcMetric(out, report);
        out.append("</tbody></table><p>参与比对 ").append(report.comparedChars)
                .append(" 个有效字符（语料侧可比候选 ").append(report.comparableCandidates).append(" 篇），命中相似 ")
                .append(report.duplicateChars).append(" 个，其中落在引用区间内 ").append(report.citedDuplicateChars).append(" 个。</p>");
        String split = DuplicateEngine.materialSplitLine(report);
        if (split.length() > 0)
            out.append("<p><strong>").append(escape(DuplicateEngine.MATERIAL_SPLIT_LABEL))
                    .append("：</strong>").append(escape(split)).append("</p>");
        abstractLayer(out, report);
    }
    /** A run that consulted nothing gets 未完成查重 as its headline; the AIGC share is local, so it stays. */
    private static void unfinished(StringBuilder out, DuplicateEngine.Report report) {
        String reason = report.retrievalReason == null ? "" : report.retrievalReason.trim();
        /* 原因与下一步在手机上是两行（ApiWorkflow 贴的是同一个串），报告里也得是两段：
           挤成一段，第二段就没人在看了。 */
        out.append("<h2>未完成查重</h2>");
        for (String part : (reason.isEmpty() ? "本次没有可比对的文献来源" : reason).split("\n")) {
            String piece = part.trim();
            if (piece.isEmpty()) continue;
            out.append("<p><strong>").append(escape(piece)).append("</strong></p>");
        }
        out.append("<p>本次没有可比对的文献材料，相似度类指标无法成立；只有 AIGC 倾向是本机计算的结果。</p>");
        // 这一张表里一个比率都没有（未完成查重不成立任何比率），列名当然也不能叫"比例"。
        out.append("<h2>指标</h2><table><thead><tr><th>指标</th><th>数值</th></tr></thead><tbody>");
        aigcMetric(out, report);
        out.append("</tbody></table>");
        /* "没有任何可比正文"这句必须留着（它说的是真的），但它下面不许是空白页：
           摘要层的数与两条能走的下一步，就排在这同一屏上。 */
        abstractLayer(out, report);
        if (DuplicateEngine.abstractLayerMeasured(report))
            out.append("<p><strong>").append(escape(DuplicateEngine.abstractLayerNextSteps())).append("</strong></p>");
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
        String shape = report.asksSent <= 0 ? "（本次设置允许 " + report.windowsPlanned + " 个）"
                : "（真问出去 " + report.asksSent + " 次，每窗 "
                        + (report.windowDepthLow == report.windowDepthHigh
                                ? String.valueOf(report.windowDepthLow)
                                : report.windowDepthLow + "-" + report.windowDepthHigh) + " 家）";
        metricText(out, "已检索窗口数", report.windowsRetrieved + "/" + report.windowsAvailable
                + " 个窗口组" + shape);
        metricText(out, "已覆盖字数", report.coveredChars + " 字（可检索正文 " + report.comparableChars + " 字）");
        metricText(out, "覆盖率", percent(rate));
        metricText(out, "可比候选文献", report.comparableCandidates + " 篇（仅摘要可比 "
                + report.abstractOnlyCandidates + " 篇，只有题录 " + report.recordOnlyCandidates + " 篇）");
        metricText(out, "开放获取全文", report.fullTextCandidates + " 篇已抓取；与检索词零共同词被挡掉 "
                + report.unrankedCandidates + " 篇");
        /* 顺手抓正文那一行只在真抓过（或该抓而没排上）的那一轮出现，没开的轮次一个字都不许多。
           措辞与结果页注记同出一处（DuplicateEngine.autoFetchLine），两处不许各写一遍。 */
        String autoFetch = DuplicateEngine.autoFetchLine(report.autoPdfTried, report.autoPdfFetched,
                report.autoPdfFailed, report.autoPdfLeft, report.autoPdfReason, report.autoPdfShapes,
                report.autoPdfSkipped);
        if (!autoFetch.isEmpty()) metricText(out, "顺手抓正文", autoFetch);
        /* 通了几个检索源单独占一行，只在"路是通的、可比正文一篇都没有"那一轮印：那一屏要分清的正是
           "站点只回了题录摘要"与"手机根本没有一条路走到检索站"，两者的下一步相反。出口那一档的原因行
           里已经带着这个数，跑通的那一轮多这一行没有新信息，报告必须与今天一字不差。 */
        String tally = DuplicateEngine.hostTallyLine(report);
        if (!tally.isEmpty() && report.retrievalIncomplete && !DuplicateEngine.noNetworkExit(report))
            metricText(out, "检索源连通", tally);
        out.append("</tbody></table>");
        if (report.retrievalPartial) {
            String reason = report.retrievalPartialReason == null ? "" : report.retrievalPartialReason.trim();
            out.append("<p><strong>").append(escape(reason.isEmpty() ? "本次未检索完全，相似率是下限" : reason))
                    .append("</strong></p>");
        }
    }
    /**
     * 这次到底比了哪些库、各多少篇。一组一行，档位直接写在组名里——"哪几库只有摘要"是这张表要回答的
     * 第一件事，藏在表注里等于没写。语料为空（一张表都没进）时整节不画。
     */
    private static void inventory(StringBuilder out, DuplicateEngine.Report report) {
        CorpusLedger ledger = report == null ? null : report.inventory;
        if (ledger == null || ledger.rows.isEmpty()) return;
        out.append("<h2>比对材料 ").append(ledger.papers).append(" 篇 &#183; ").append(ledger.groups)
                .append(" 组</h2><table><thead><tr><th>来源 &#183; 可比材料</th><th>篇数</th>")
                .append("<th>比对字数</th></tr></thead><tbody>");
        for (int i = 0; i < ledger.rows.size(); i++) {
            CorpusLedger.Row row = ledger.rows.get(i);
            out.append("<tr><td>").append(escape(row.label())).append("</td><td>").append(row.papers)
                    .append("</td><td>").append(row.chars).append("</td></tr>");
        }
        out.append("</tbody></table>");
        out.append("<p>「只有精要可读」那一档比的是网站从整篇里自己摘的那一段，「只有摘要可比」那一档比的是摘要："
                + "撞上几句只说明那几句在里面，正文没比过。各档字数各记各的，不相加成一个结论。</p>");
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
                .append("<th>提问次数</th></tr></thead><tbody>");
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
                report.comparedChars, report.structureSpans);
        if (ledger.rows.isEmpty()) {
            // 什么都没比对成：头部已经是"未完成查重"，再排一张空来源榜会被读成"这篇很干净"。
            if (report.retrievalIncomplete) return;
            ledgerNoHit(out, report);
            return;
        }
        out.append("<h2>来源榜 &#183; 按文献 &#183; 共 ").append(ledger.paperCount).append(" 篇命中</h2>");
        out.append("<table><thead><tr><th>#</th><th>文献</th><th>检索源</th><th>可比材料</th><th>重复字符</th>")
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
            out.append("</td><td>").append(escape(row.engine)).append("</td><td>")
                    .append(escape(ledgerMaterial(row))).append("</td><td>").append(row.duplicateChars)
                    .append("</td><td>").append(percent(row.share(ledger.comparedChars)))
                    .append("</td><td>").append(row.hitCount).append("</td><td>");
            ledgerIdentifier(out, row);
            out.append("</td></tr>");
        }
        out.append("</tbody></table>");
        out.append("<p>各篇重复率用整篇有效字数 ").append(ledger.comparedChars)
                .append(" 个有效字符当同分母，所以各行相加就是总相似度比；同一段字符只记给命中最长的那一篇。</p>");
        out.append("<p>").append(escape(DuplicateEngine.MATERIAL_SPLIT_LABEL)).append("：正文级 ")
                .append(ledger.fullDuplicateChars).append(" 字（").append(ledger.fullPapers)
                .append(" 篇）&#183; 只有精要可读 ").append(ledger.digestDuplicateChars).append(" 字（")
                .append(ledger.digestPapers).append(" 篇）&#183; 只有摘要可比 ")
                .append(ledger.abstractDuplicateChars).append(" 字（").append(ledger.abstractPapers)
                .append(" 篇）。各档各记各的，不相加成一个结论；排序先分档再比字数，"
                        + "精要级与摘要级都排在正文级那几行后面。</p>");
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

    /**
     * 材料档那一格。并过条的行可能几档都有字数，那种行必须把每一份都写出来——只写"正文"那两个字
     * 就等于把这一行里精要级、摘要级那两笔账盖掉，而这一轮要防的正是这种盖法。
     */
    private static String ledgerMaterial(SourceLedger.Row row) {
        int parts = (row.fullChars > 0 ? 1 : 0) + (row.digestChars > 0 ? 1 : 0)
                + (row.abstractChars > 0 ? 1 : 0);
        if (parts <= 1) return SourceLedger.materialLabel(row);
        StringBuilder out = new StringBuilder();
        if (row.fullChars > 0) out.append("正文 ").append(row.fullChars).append(" 字");
        if (row.digestChars > 0)
            out.append(out.length() > 0 ? " + " : "").append("精要 ").append(row.digestChars).append(" 字");
        if (row.abstractChars > 0)
            out.append(out.length() > 0 ? " + " : "").append("摘要 ").append(row.abstractChars).append(" 字");
        return out.toString();
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
        // 判据未标定：句分那一列整列撤掉，只留"这句触发过哪几条判据"。留一个 0.00%~50.00% 的数在这里，
        // 就是拿一个方向反了的刻度冒充百分比（降级做法同"未完成查重不印比率"）。
        boolean scored = AigcScorer.calibrated();
        out.append("<table><thead><tr><th>句子</th>");
        if (scored) out.append("<th>倾向分</th>");
        out.append("<th>判定依据</th></tr></thead><tbody>");
        for (AigcDetector.Sentence sentence : aigc.sentences) {
            StringBuilder features = new StringBuilder();
            for (String feature : sentence.features) {
                if (features.length() > 0) features.append("&#32178;");
                features.append(escape(feature));
            }
            out.append("<tr><td>").append(escape(snippet(report.sourceText, sentence.start, sentence.end))).append("</td>");
            if (scored) out.append("<td>").append(percent(sentence.score * 100)).append("</td>");
            out.append("<td>").append(features).append("</td></tr>");
        }
        out.append("</tbody></table>");
        if (!scored)
            out.append("<p>上表按原文顺序列，<strong>不是可疑度排序</strong>：现判据在标注语料上把真人句分排在机器句分之上，"
                    + "任何排序都会把真人句子排到前面。要按可疑度排序，先等判据方向被带标注语料验正。</p>");
    }
    /**
     * 这一格只说档位与字数，不说比例（0.7.1）：0.7.0 它叫"AIGC 生成比例"、打的却是字符加权句分，
     * 与同一张表里的总相似度比是两把尺子，报告里读错概率最高的就是这一格。现在它是
     * "机器生成倾向：复核（可疑 412 字 / 全文 6300 字）"——档位是 AigcDetector.Tier 的人话名字，
     * 后面两个都是绝对量。样本不足时只报差多少字，一个百分号都不印；
     * 字符加权句分留在"机器腔均分"那一行，名字里就写清它不是占比。文案与结果面板同源（DuplicateEngine）。
     */
    private static void aigcMetric(StringBuilder out, DuplicateEngine.Report report) {
        metricText(out, "机器生成倾向", DuplicateEngine.aigcTrend(report));
        String score = DuplicateEngine.aigcScoreLine(report);
        if (!score.isEmpty()) metricText(out, DuplicateEngine.AIGC_SCORE_LABEL, score);
    }
    /**
     * 摘要层（近似）那一屏。口径长在标题上（公开检索只到摘要），数字自带分子分母与阈值，
     * 表里是逐句证据：本文那句、撞上的摘要那句、来源题名。
     * 排在指标表后面自成一张卡——正文级的头条在上，这一层在它下面，两个数不同表也不相加。
     */
    private static void abstractLayer(StringBuilder out, DuplicateEngine.Report report) {
        if (!DuplicateEngine.abstractLayerMeasured(report)) return;
        out.append("<h2>").append(escape(DuplicateEngine.ABSTRACT_LAYER_LABEL)).append("</h2>");
        out.append("<table><thead><tr><th>指标</th><th>数值</th></tr></thead><tbody>");
        metricText(out, "摘要层重合率（按句）", DuplicateEngine.abstractLayerLine(report));
        out.append("</tbody></table>");
        out.append("<p>").append(escape(DuplicateEngine.abstractLayerCaveat(report))).append("</p>");
        if (report.abstractHits.isEmpty()) {
            out.append("<p>没有一句撞上摘要。这只说明这批候选的摘要里没有与本文句子同形的句子，")
                    .append("不等于正文没有重复——公开检索口给不到正文那一层。</p>");
            return;
        }
        out.append("<table><thead><tr><th>本文句子</th><th>撞上的摘要句</th><th>来源题名</th>")
                .append("<th>检索源</th><th>袋 Dice</th></tr></thead><tbody>");
        for (DuplicateEngine.AbstractHit hit : report.abstractHits) {
            out.append("<tr><td>").append(escape(snippet(report.sourceText, hit.start, hit.end))).append("</td><td>")
                    .append(escape(clip(hit.abstractSentence, 240))).append("</td><td>")
                    .append(escape(hit.title)).append("</td><td>")
                    .append(escape(PaperSources.label(hit.engine))).append("</td><td>")
                    .append(percent(hit.score * 100d)).append("</td></tr>");
        }
        out.append("</tbody></table>");
        if (report.abstractHits.size() < report.abstractSentencesMatched)
            out.append("<p>上表只列分数最高的 ").append(report.abstractHits.size()).append(" 句，本轮共命中 ")
                    .append(report.abstractSentencesMatched).append(" 句。</p>");
    }

    /**
     * 逐次检索响应留档（A4）：一问一行，成功也留。三个数分开写——
     * 本机解出几条、源声称几条、这份响应是什么形状。0% 从此在档里一眼分得清。
     */
    private static void shapes(StringBuilder out, DuplicateEngine.Report report) {
        if (report == null || report.shapes.isEmpty()) return;
        out.append("<h2>检索响应留档 ").append(report.shapes.size()).append(" 次</h2>");
        out.append("<table><thead><tr><th>检索源</th><th>检索式</th><th>状态</th><th>字节</th>")
                .append("<th>本机解出条目</th><th>源声称总数</th><th>响应形状</th><th>响应开头</th></tr></thead><tbody>");
        for (PaperSources.ShapeRow row : report.shapes) {
            out.append("<tr><td>").append(escape(PaperSources.label(row.engine))).append("</td><td>")
                    .append(escape(row.probe)).append("</td><td>")
                    .append(row.status < 0 ? "无响应" : String.valueOf(row.status)).append("</td><td>")
                    .append(row.bodyBytes < 0 ? "?" : String.valueOf(row.bodyBytes)).append("</td><td>")
                    .append(String.valueOf(row.entries)).append("</td><td>")
                    .append(row.declaredTotal < 0L ? "未声明" : String.valueOf(row.declaredTotal)).append("</td><td>")
                    .append(escape(row.shape)).append("</td><td>")
                    .append(escape(row.error.isEmpty() ? row.excerpt : row.error)).append("</td></tr>");
        }
        out.append("</tbody></table>");
        out.append("<p>“本机解出条目”是这一份响应里我们读出了几条；“源声称总数”是响应里源自己写的命中条数，")
                .append("“未声明”表示这个检索口不写这个数字，不是 0。形状里 declared-zero = 源明说这一式没有货，")
                .append("blocked = 挡人页（验证/拦截），js-shell = 只有脚本壳没有条目，empty-frame = 空帧，")
                .append("frame-unparsed = 帧里有著录项但我们解不出，no-response = 请求没走通。</p>");
        if (report.shapesDropped > 0)
            out.append("<p>另有 ").append(report.shapesDropped).append(" 行超出留档上限，未写进本报告。</p>");
    }

    private static String clip(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
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

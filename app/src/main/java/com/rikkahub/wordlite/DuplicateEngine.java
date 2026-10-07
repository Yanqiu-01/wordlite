package com.rikkahub.wordlite;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Duplication and AIGC orchestration: citation marking, bounded retrieval, corpus match, one report. */
public final class DuplicateEngine {
    /* 检索短语上限 48 字：实测维普对 160 字的句子返回 0 条，同一篇摘要截到 40 字就返回 20 多条，
       OpenAlex / Crossref 这类源在短查询上也没有变差。 */
    static final int WINDOW_PARAGRAPHS = 3, MAX_WINDOWS = 24, MAX_PHRASE_CHARS = 48;
    static final int MAX_REQUESTS = 72, MAX_FULL_TEXTS = 6, MAX_CANDIDATES = 120, MAX_NOTES = 40;
    /** Retrieval gaps phrased with the note(...) vocabulary so the headline and the notes never disagree. */
    static final String GAP_NOTHING_RETRIEVED = "联网检索没有取回可比对的候选文献";
    static final String GAP_CANCELLED = "检索已取消，没有联网取候选文献";
    static final String GAP_NO_SOURCE = "没有可用的检索源，本次没有执行联网检索";
    static final String GAP_EMPTY_LIBRARY = "未启用联网检索，自建库为空，没有可比对的语料";
    public interface Progress { void step(String label, int done, int total); }
    public static final class Report {
        public double overallRate, excludingCitationsRate, selfWrittenRate, aigcRate;
        public int comparedChars, duplicateChars, citedDuplicateChars;
        /** 按论文结构排除在比对之外的字数（参考文献表、致谢、附录、目录）。 */
        public int excludedChars;
        public long elapsedMillis;
        public String detectedAt = "";
        public final ArrayList<TextCorpus.Hit> hits = new ArrayList<TextCorpus.Hit>();
        public final ArrayList<PaperSources.Candidate> candidates = new ArrayList<PaperSources.Candidate>();
        public final LinkedHashMap<String, Double> byEngine = new LinkedHashMap<String, Double>();
        public final LinkedHashMap<String, Integer> candidateCount = new LinkedHashMap<String, Integer>();
        public AigcDetector.Result aigc;
        /** AIGC 样本不足时，比例不该被当成结论。 */
        public boolean aigcInsufficient;
        public String aigcVerdict = "";
        public final ArrayList<String> notes = new ArrayList<String>();
        /** Scanned text so the report can cut snippets without re-opening the document. */
        public String sourceText = "";
        /** True when the run consulted nothing, so the rates must not read as "nothing is duplicated". */
        public boolean retrievalIncomplete;
        /** Why the run is unfinished, in the same wording as the notes; null while the run is complete. */
        public String retrievalReason;
    }
    private DuplicateEngine() { }

    public static Report scan(TextSelection selection, TextCorpus corpus, boolean useWeb, ArrayList<String> engines,
                              PaperSources.Limits limits, ApiClient.Cancellation cancellation, Progress progress) {
        long started = System.nanoTime();
        Report report = new Report();
        String text = selection == null || selection.text == null ? "" : selection.text;
        report.sourceText = text;
        TextCorpus library = corpus == null ? new TextCorpus() : corpus;
        PaperSources.Limits safe = limits == null ? new PaperSources.Limits() : limits;
        ArrayList<String> wanted = new ArrayList<String>();
        boolean aborted = false;
        try {
            wanted.addAll(pick(engines, report));
            int[] spans = citationSpans(selection, null);
            if (spans.length >= 2) note(report, "已标出 " + (spans.length / 2) + " 处引用段落，这部分只计入总相似度比");
            if (!useWeb) note(report, "未启用联网检索，只与自建库比对");
            else if (cancelled(cancellation)) note(report, GAP_CANCELLED);
            else search(text, library, report, wanted, safe, cancellation, progress);
            if (library.isEmpty()) note(report, "自建库为空，比对基线只有检索到的候选文献摘要");
            TextCorpus.Report matched = null;
            TextCorpus.Structure structure = TextCorpus.structure(text);
            if (cancelled(cancellation)) note(report, "检测到取消，未执行语料比对");
            else {
                step(progress, "比对语料", 2, 4);
                matched = library.match(text, spans, structure.spanArray());
                if (!structure.isEmpty())
                    note(report, "已排除结构性文本 " + structure.excludedChars + " 字：参考文献表 "
                            + structure.bibliographySections + " 节共 " + structure.citationLines
                            + " 条，致谢/附录/目录等 " + structure.otherSections + " 节；这部分不计入相似率");
            }
            if (cancelled(cancellation)) note(report, "检测到取消，未执行 AIGC 倾向分析");
            else {
                step(progress, "AIGC 倾向分析", 3, 4);
                int[] quoted = TextCorpus.mergeSpans(concat(spans, structure.spanArray()), text.length());
                report.aigc = AigcDetector.detect(text, quoted);
                if (report.aigc.excludedChars > 0)
                    note(report, "AIGC 分析跳过引用与结构性文本 " + report.aigc.excludedChars + " 字");
            }
            rates(report, matched);
            step(progress, "汇总报告", 4, 4);
        } catch (RuntimeException error) {
            note(report, "检测中断：" + message(error));
            aborted = true;
        }
        markRetrievalGap(report, useWeb, wanted, library, cancellation, aborted);
        report.elapsedMillis = (System.nanoTime() - started) / 1000000;
        report.detectedAt = ReviewManager.now();
        return report;
    }

    /** Nothing consulted must never read as nothing duplicated: name the gap so the report can say so. */
    private static void markRetrievalGap(Report report, boolean useWeb, ArrayList<String> engines,
                                         TextCorpus corpus, ApiClient.Cancellation cancellation, boolean aborted) {
        if (report.retrievalIncomplete) return;
        if (!useWeb) {
            if (corpus.isEmpty()) gap(report, GAP_EMPTY_LIBRARY);
            return;
        }
        if (engines.isEmpty()) { gap(report, GAP_NO_SOURCE); return; }
        if (!report.candidates.isEmpty()) return;
        if (cancelled(cancellation)) gap(report, GAP_CANCELLED);
        else if (aborted) gap(report, GAP_NOTHING_RETRIEVED);
    }
    /** The first named gap wins, so the sharpest reason is the one search() found. */
    private static void gap(Report report, String reason) {
        if (report.retrievalIncomplete) return;
        report.retrievalIncomplete = true;
        report.retrievalReason = reason;
    }

    private static void rates(Report report, TextCorpus.Report matched) {
        if (matched != null) {
            report.hits.addAll(matched.hits);
            report.comparedChars = matched.comparedChars;
            report.duplicateChars = matched.duplicateChars;
            report.citedDuplicateChars = matched.citedDuplicateChars;
            report.excludedChars = matched.excludedChars;
            report.overallRate = clamp(matched.overallRate);
            report.excludingCitationsRate = clamp(matched.excludingCitationsRate);
            for (Map.Entry<String, Double> entry : matched.byEngine.entrySet())
                report.byEngine.put(entry.getKey(), clamp(entry.getValue() == null ? 0 : entry.getValue().doubleValue()));
        }
        if (report.aigc != null) {
            report.aigcRate = clamp(report.aigc.rate);
            report.aigcInsufficient = report.aigc.insufficientSample;
            report.aigcVerdict = report.aigc.verdict == null ? "" : report.aigc.verdict;
            int flagged = 0;
            for (AigcDetector.Sentence sentence : report.aigc.sentences)
                if (sentence.score >= 0.5f) flagged += Math.max(0, sentence.end - sentence.start);
            int base = report.comparedChars > 0 ? report.comparedChars : report.aigc.comparedChars;
            double share = base > 0 ? flagged * 100d / base : report.aigcRate;
            // 样本不足时那份倾向连自编率都不该拉动。
            if (report.aigcInsufficient) share = 0d;
            report.selfWrittenRate = clamp(100 - report.overallRate - clamp(share));
        } else report.selfWrittenRate = clamp(100 - report.overallRate);
    }
    /** 两段成对区间接在一起，交给 mergeSpans 合并。 */
    static int[] concat(int[] first, int[] second) {
        int a = first == null ? 0 : first.length;
        int b = second == null ? 0 : second.length;
        int[] out = new int[a + b];
        System.arraycopy(first, 0, out, 0, a);
        if (b > 0) System.arraycopy(second, 0, out, a, b);
        return out;
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0;
        return value < 0 ? 0 : value > 100 ? 100 : value;
    }

    private static ArrayList<String> pick(ArrayList<String> requested, Report report) {
        ArrayList<String> known = PaperSources.engines();
        ArrayList<String> out = new ArrayList<String>();
        if (requested == null || requested.isEmpty()) return known;
        for (String engine : requested) {
            String name = engine == null ? "" : engine.trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            if (name.equals("semantic scholar") || name.equals("semanticscholar")) name = "semantic-scholar";
            if (name.equals("europe-pmc") || name.equals("pmc")) name = "europepmc";
            if (!known.contains(name)) { note(report, "未知检索源 " + name + " 已忽略"); continue; }
            if (!out.contains(name)) out.add(name);
        }
        if (out.isEmpty()) { note(report, "没有可用的检索源，只与自建库比对"); return new ArrayList<String>(); }
        return out;
    }

    /** Windowed retrieval: three paragraphs per query, capped windows, capped candidates per engine. */
    private static void search(String text, TextCorpus corpus, Report report, ArrayList<String> engines,
                               PaperSources.Limits limits, ApiClient.Cancellation cancellation, Progress progress) {
        if (engines.isEmpty()) return;
        ArrayList<String> windows = windows(text);
        if (windows.isEmpty()) { note(report, "正文没有可用于检索的段落"); return; }
        LinkedHashMap<String, Boolean> skipped = new LinkedHashMap<String, Boolean>();
        LinkedHashMap<String, Boolean> empty = new LinkedHashMap<String, Boolean>();
        int requests = 0, fullTexts = 0, failures = 0, total = windows.size() * engines.size(), done = 0;
        for (String window : windows) {
            String phrase = PaperSources.queryPhrase(window, MAX_PHRASE_CHARS);
            for (String engine : engines) {
                done++;
                if (cancelled(cancellation)) { note(report, "检索已取消，结果只覆盖已完成的窗口"); return; }
                if (phrase.isEmpty() || Boolean.TRUE.equals(skipped.get(engine))) continue;
                if (count(report.candidateCount, engine) >= limits.perEngine) continue;
                if (requests >= MAX_REQUESTS) {
                    note(report, "已达单次检测的检索请求上限 " + MAX_REQUESTS + " 次，剩余窗口未检索");
                    everyConnectorFailed(report, engines, skipped);
                    return;
                }
                requests++;
                step(progress, "检索 " + PaperSources.label(engine), done, total);
                ArrayList<PaperSources.Candidate> found;
                try { found = PaperSources.search(engine, phrase, limits, cancellation); }
                catch (IllegalArgumentException error) {
                    skipped.put(engine, Boolean.TRUE); failures++;
                    note(report, "已跳过 " + PaperSources.label(engine) + "：" + message(error));
                    continue;
                } catch (IOException error) {
                    skipped.put(engine, Boolean.TRUE); failures++;
                    note(report, "已跳过 " + PaperSources.label(engine) + "：" + message(error));
                    continue;
                }
                if (found.isEmpty()) { empty.put(engine, Boolean.TRUE); continue; }
                for (PaperSources.Candidate candidate : found) {
                    if (cancelled(cancellation) || report.candidates.size() >= MAX_CANDIDATES
                            || count(report.candidateCount, engine) >= limits.perEngine) break;
                    if (known(report.candidates, candidate)) continue;
                    String body = candidate.abstractText == null ? "" : candidate.abstractText;
                    if (!candidate.fullTextUrl.isEmpty() && fullTexts < MAX_FULL_TEXTS) {
                        fullTexts++;
                        try {
                            String fetched = PaperSources.fullText(candidate, limits, cancellation);
                            if (fetched != null && !fetched.trim().isEmpty())
                                body = body.isEmpty() ? fetched : body + "\n" + fetched;
                        } catch (IOException error) { note(report, "全文抓取失败，改用摘要比对：" + message(error)); }
                    }
                    report.candidates.add(candidate);
                    corpus.add(candidate.source, body);
                    bump(report.candidateCount, candidate.source.engine);
                }
                /* 只有所有窗口都空手才算"未命中"：第一个窗口没查到、后面的窗口查到了，不该报未命中。 */
                if (count(report.candidateCount, engine) > 0) empty.remove(engine);
            }
        }
        for (String engine : engines)
            if (Boolean.TRUE.equals(empty.get(engine)) && !Boolean.TRUE.equals(skipped.get(engine)))
                note(report, PaperSources.label(engine) + " 未命中相关文献");
        if (report.candidates.isEmpty()) note(report, GAP_NOTHING_RETRIEVED);
        else note(report, "共取回 " + report.candidates.size() + " 篇候选文献，其中 " + fullTexts + " 篇尝试了开放获取全文");
        if (requests >= MAX_REQUESTS) note(report, "检索请求已达上限 " + MAX_REQUESTS + " 次");
        if (failures > 0) note(report, failures + " 个检索源本次不可用");
        everyConnectorFailed(report, engines, skipped);
    }
    /** Every wanted connector threw and nothing usable came back: the web half of the run never happened. */
    private static void everyConnectorFailed(Report report, ArrayList<String> engines,
                                             LinkedHashMap<String, Boolean> skipped) {
        if (report.candidates.isEmpty() && !engines.isEmpty() && skipped.size() >= engines.size())
            gap(report, engines.size() + " 个检索源本次全部不可用，" + GAP_NOTHING_RETRIEVED);
    }
    private static boolean known(ArrayList<PaperSources.Candidate> candidates, PaperSources.Candidate candidate) {
        String left = !candidate.source.locator.isEmpty() ? candidate.source.locator : candidate.source.title;
        if (left.isEmpty()) return true;
        for (PaperSources.Candidate known : candidates) {
            String right = !known.source.locator.isEmpty() ? known.source.locator : known.source.title;
            if (left.equals(right) && known.source.engine.equals(candidate.source.engine)) return true;
        }
        return false;
    }
    /**
     * 三个段落一组，最多 MAX_WINDOWS 组。封面行、目录行、图表注这类行拿去检索只会命中"毕业论文 专业
     * 设计"这种通用词，实测会把候选池污染成教学管理论文：短于 20 字的段和不以句号结尾而以页码收尾的段
     * （目录行就是"2.2.1 SiC高温封装与TLP互连技术4"这个形状）都不进窗口。
     */
    static final int MIN_WINDOW_PARAGRAPH_CHARS = 20;

    /** 目录行、图表注的共同形状：结尾是一个裸页码。 */
    static boolean retrievable(String paragraph) {
        return paragraph.length() >= MIN_WINDOW_PARAGRAPH_CHARS
                && !Character.isDigit(paragraph.charAt(paragraph.length() - 1));
    }
    static ArrayList<String> windows(String text) {
        ArrayList<String> paragraphs = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            boolean cut = i == text.length() || text.charAt(i) == '\n';
            if (!cut) { current.append(text.charAt(i)); continue; }
            String value = current.toString().trim();
            if (retrievable(value)) paragraphs.add(value);
            current.setLength(0);
        }
        ArrayList<String> out = new ArrayList<String>();
        StringBuilder group = new StringBuilder();
        int taken = 0;
        for (int i = 0; i < paragraphs.size(); i++) {
            if (group.length() > 0) group.append(' ');
            group.append(paragraphs.get(i));
            if (i % WINDOW_PARAGRAPHS != WINDOW_PARAGRAPHS - 1 && i != paragraphs.size() - 1) continue;
            out.add(group.toString());
            group.setLength(0);
            if (++taken >= MAX_WINDOWS) break;
        }
        return out;
    }

    /** {start,end} pairs over the selection text: reference paragraphs plus explicit quotations. */
    public static int[] citationSpans(TextSelection selection, DocxDocument document) {
        if (selection == null) return new int[0];
        String text = selection.text == null ? "" : selection.text;
        ArrayList<int[]> spans = new ArrayList<int[]>();
        ArrayList<Integer> section = referenceSection(document);
        for (TextSelection.Piece piece : selection.pieces) {
            int start = piece.submittedStart, end = piece.submittedStart + piece.original.length();
            if (end <= start) continue;
            DocxDocument.ParagraphBlock paragraph = document == null ? null : TextSelection.find(document, piece.paragraphIndex);
            boolean marked;
            if (paragraph == null) marked = referenceEntry(piece.original);
            else marked = TextProtection.referenceParagraph(paragraph) || referenceEntry(piece.original)
                    || section.contains(Integer.valueOf(piece.paragraphIndex))
                    && (referenceEntry(piece.original) || TextCorpus.citationLike(piece.original));
            if (marked) spans.add(new int[]{ start, end });
        }
        quotations(text, spans);
        return merge(spans, text.length());
    }
    /** Paragraph indexes that sit under a 参考文献 / References heading, until a blank line or next heading. */
    private static ArrayList<Integer> referenceSection(DocxDocument document) {
        ArrayList<Integer> out = new ArrayList<Integer>();
        if (document == null) return out;
        boolean inside = false;
        for (DocxDocument.ParagraphBlock paragraph : document.paragraphs) {
            String trimmed = paragraph.text.trim();
            boolean heading = referenceHeading(trimmed);
            if (paragraph.isHeading || heading) { inside = heading; continue; }
            if (trimmed.isEmpty()) { inside = false; continue; }
            if (inside) out.add(Integer.valueOf(paragraph.index));
        }
        return out;
    }
    private static boolean referenceHeading(String text) {
        String value = text.replace(" ", "").replace('\u3000', ' ').trim().toLowerCase(Locale.ROOT);
        return value.equals("参考文献") || value.equals("引用文献") || value.equals("参考书目") || value.equals("references")
                || value.equals("reference") || value.equals("bibliography") || value.equals("works cited");
    }
    /** Conservative entry shape: numbered or bracketed lead plus a publication year. */
    static boolean referenceEntry(String text) {
        String value = text == null ? "" : text.trim();
        if (value.length() < 20 || value.length() > 600) return false;
        int at = 0;
        boolean lead = value.charAt(0) == '[' || value.charAt(0) == '［';
        if (!lead) {
            int digits = 0;
            while (at < value.length() && value.charAt(at) >= '0' && value.charAt(at) <= '9') { at++; digits++; }
            lead = digits >= 1 && digits <= 3 && at < value.length() && (value.charAt(at) == ']' || value.charAt(at) == ')'
                    || value.charAt(at) == '）' || value.charAt(at) == '.' || value.charAt(at) == '、');
        }
        return lead && !yearIn(value).isEmpty();
    }
    private static String yearIn(String value) {
        for (int i = 0; i + 3 < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') continue;
            int digits = 0;
            while (i + digits < value.length() && value.charAt(i + digits) >= '0' && value.charAt(i + digits) <= '9') digits++;
            if (digits == 4) {
                int parsed = Integer.parseInt(value.substring(i, i + 4));
                if (parsed >= 1500 && parsed <= 2200) return String.valueOf(parsed);
            }
            i += digits;
        }
        return "";
    }
    private static final char[][] QUOTES = { { '\u201C', '\u201D' }, { '\u300C', '\u300D' }, { '\u300E', '\u300F' },
            { '"', '"' }, { '\u300A', '\u300B' } };
    private static void quotations(String text, ArrayList<int[]> spans) {
        for (int i = 0; i < QUOTES.length; i++) {
            int at = 0;
            while (true) {
                int start = text.indexOf(QUOTES[i][0], at);
                if (start < 0) break;
                int end = text.indexOf(QUOTES[i][1], start + 1);
                if (end < 0) break;
                int stop = Math.min(end + 1, text.length());
                if (stop - start >= 8 && stop - start <= 4000) spans.add(new int[]{ start, stop });
                at = stop;
            }
        }
    }
    private static int[] merge(ArrayList<int[]> spans, int length) {
        if (spans.isEmpty()) return new int[0];
        for (int[] span : spans) {
            if (span[0] < 0) span[0] = 0;
            if (span[1] > length) span[1] = length;
        }
        Collections.sort(spans, new Comparator<int[]>() {
            public int compare(int[] left, int[] right) { return left[0] != right[0] ? left[0] - right[0] : left[1] - right[1]; }
        });
        ArrayList<int[]> merged = new ArrayList<int[]>();
        for (int[] span : spans) {
            if (span[1] <= span[0]) continue;
            if (!merged.isEmpty() && span[0] - merged.get(merged.size() - 1)[1] <= 1)
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], span[1]);
            else merged.add(new int[]{ span[0], span[1] });
        }
        int[] out = new int[merged.size() * 2];
        for (int i = 0; i < merged.size(); i++) {
            out[i * 2] = merged.get(i)[0];
            out[i * 2 + 1] = merged.get(i)[1];
        }
        return out;
    }

    private static boolean cancelled(ApiClient.Cancellation cancellation) {
        return Thread.currentThread().isInterrupted() || cancellation != null && cancellation.cancelled();
    }
    private static void step(Progress progress, String label, int done, int total) {
        if (progress != null) progress.step(label, done, total);
    }
    private static void note(Report report, String value) {
        if (report.notes.size() < MAX_NOTES && !report.notes.contains(value)) report.notes.add(value);
    }
    private static int count(LinkedHashMap<String, Integer> counts, String engine) {
        Integer value = counts.get(engine);
        return value == null ? 0 : value.intValue();
    }
    private static void bump(LinkedHashMap<String, Integer> counts, String engine) {
        String name = engine == null ? "unknown" : engine;
        counts.put(name, Integer.valueOf(count(counts, name) + 1));
    }
    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value.trim();
    }
}

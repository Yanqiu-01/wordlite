package com.rikkahub.wordlite;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 报告中心的数据层（1.0.0）：一次跑完的查重落成一条磁盘记录，之后详情页只读盘就能把整张报告重画出来，
 * 不必重跑比对——重跑要再花掉一轮检索配额，而且语料变了以后画出来的根本不是当时那份。
 *
 * 落盘格式照抄 {@link LocalLibrary}：目录里一份 index.json 存列表用的摘要，正文各占一个文件。
 * 为什么不让 index.json 直接装下全部记录：列表页只要 60 行摘要，把整份证据表跟着读进内存
 * 是白付 60 x 512 KB 的代价；而记录文件坏了只崩掉那一条，索引还能按目录重建。
 *
 * 三条硬规矩，都由 ReportStoreRegression 钉着：
 * 1. 写进去什么，读出来就是什么（{@link Record#toJson()} 逐字符相同），指标/来源/证据一条不许偷偷丢。
 * 2. 体积有上限：一条记录最多 {@link #MAX_RECORD_BYTES} 字节、库里最多 {@link #MAX_RECORDS} 条。
 *    超限先砍 HTML（详情页不靠它渲染），再砍证据尾巴，砍剩一条还装不下就拒收——不写半条记录。
 * 3. 索引坏了按目录重建，单条记录坏了只丢那一条，其余照旧列得出来。
 *
 * 本类不 import android.*：全部回归跑在纯 JVM 上，和 tests/*.java 其余套件同一个门槛。
 */
public final class ReportStore {
    /** 索引文件名与结构沿用 LocalLibrary 的写法：{"version":1,"records":[...]}。 */
    private static final String INDEX_NAME = "index.json";
    private static final String RECORD_SUFFIX = ".json";
    public static final int RECORD_VERSION = 1;

    /* 条数上限：一屏 60 条历史够翻几天了，再多是本机该丢旧账的时候，不是该卡的时候。 */
    public static final int MAX_RECORDS = 60;
    /* 单条上限 512 KB：一份 3 万字论文的 HTML 报告实测几十到几百 KB，留出十倍的余量再封顶，
       为的是 60 条最坏也就 30 MB，手机私有目录受得住。数字写死在这里，回归按同一个数断言。 */
    public static final int MAX_RECORD_BYTES = 512 * 1024;
    public static final int MAX_INDEX_BYTES = 256 * 1024;
    /** 证据条数上限：面板只画这些，超出部分写进 evidenceTotal，界面上说清"只保留了前 N 条"。 */
    public static final int MAX_EVIDENCE = 40;
    /** AI 分段的上限：命中地图要的是位置分布，八十段足够画出形状，超出部分只报总数。 */
    public static final int MAX_AI_SEGMENTS = 80;
    /** 来源榜行数 = SourceLedger.MAX_ROWS + 折叠行，折叠行本身就代表"其余 N 篇"，不能再砍。 */
    public static final int MAX_SOURCE_ROWS = SourceLedger.MAX_ROWS + 1;
    public static final int MAX_ENGINE_ROWS = 16;
    public static final int MAX_NOTES = 12;
    public static final int MAX_NOTE_CHARS = 200;
    /** 摘要宽度与 ApiWorkflow.safeSlice 同源：两处各写一个数，早晚一边 160 一边 200。 */
    public static final int MAX_SNIPPET_CHARS = 160;
    /** HTML 是导出用的第二等公民，先按字符数夹一刀，字节数再由 MAX_RECORD_BYTES 兜底。
       160 K 字全中文是 480 KB，加满证据表就会越过单条上限——那一刀由 shrinkToFit 补，两条上限都得是真拦得住东西的。 */
    public static final int MAX_HTML_CHARS = 160 * 1024;
    public static final int MAX_NAME_CHARS = 120;

    /** 检索三态的名字：面板、详情页、HTML 三处共用，不许各起一名。 */
    public static final String STATE_COMPLETE = "完整检索";
    public static final String STATE_PARTIAL = "部分完成";
    public static final String STATE_UNFINISHED = "未完成查重";

    /** 证据表的一行：一个字符区间加它的出处，点它就要能跳回正文那一段。 */
    public static final class Evidence {
        public int start, end;
        public double score;
        /** 命中出处：TextCorpus.CHANNEL_VERBATIM（字面证据）或 CHANNEL_REWRITE（抗改写判据）。命中地图按它分色。 */
        public int channel;
        public String title = "", engine = "", year = "", snippet = "";
        /** snippet 被 MAX_SNIPPET_CHARS 砍过，界面上要写省略号而不是让人以为原文就这么长。 */
        public boolean snippetCut;

        /** 区间是否还能用：起止都在原文内且 end > start。区间一坏，跳转就会指到别处去。 */
        public boolean usable(int sourceChars) {
            return start >= 0 && end > start && end <= sourceChars;
        }
    }

    /** 来源分布的一行（按文献聚合），直接抄 {@link SourceLedger.Row} 的展示字段。 */
    /** AI 判定落在正文上的一段区间。口径与 AigcDetector.Segment 一致，只是能落盘、能重画。 */
    public static final class AiSegment {
        public int start, end;
        public int chars;
        public double score;
        public boolean flagged;

        public boolean usable(int totalChars) {
            return start >= 0 && end > start && end <= totalChars;
        }
    }

    public static final class SourceRow {
        public String key = "", title = "", authors = "", year = "", engine = "", locator = "";
        public int duplicateChars, hitCount, sourceCount;
        /** 最早命中的起始偏移，点来源行也跳这里；没有命中时为 -1。 */
        public int firstStart = -1;
        public double share;
        public boolean others;
        public int othersCount;
    }

    /** 「按检索源分布」那一列：一个检索源一行。 */
    public static final class EngineRow {
        public String engine = "";
        public double share;
        public int candidates, windows;
    }

    /** 列表页读的那几列。列表只读 index.json，不碰记录文件。 */
    public static final class Summary {
        public String id = "", fileName = "", detectedAt = "", state = STATE_COMPLETE, reason = "";
        public long createdAt, elapsedMillis;
        public double overallRate;
        public int comparedChars, duplicateChars, evidenceTotal;
        /** 记录文件落盘后的字节数，也是体积封顶的实测值。 */
        public int bytes;
        public boolean truncated;

        static Summary of(Record record) {
            Summary summary = new Summary();
            summary.id = record.id;
            summary.fileName = record.fileName;
            summary.detectedAt = record.detectedAt;
            summary.state = record.state;
            summary.reason = record.reason;
            summary.createdAt = record.createdAt;
            summary.elapsedMillis = record.elapsedMillis;
            summary.overallRate = record.overallRate;
            summary.comparedChars = record.comparedChars;
            summary.duplicateChars = record.duplicateChars;
            summary.evidenceTotal = record.evidenceTotal;
            summary.bytes = record.bytes;
            summary.truncated = record.evidenceTruncated || record.htmlCut || record.notesTotal > record.notes.size();
            return summary;
        }
    }

    /**
     * 一份完整报告。字段按"重画一次报告需要什么"来选，不需要的一个不存：
     * 指标卡要四个比率、覆盖小节要三态与那几组数、来源分布要两类行、证据表要区间与出处，
     * 导出要 HTML。比对用的语料本体（{@code Report.baseline}）不存——那是几万条句子，
     * 报告里也没人再读它。
     */
    public static final class Record {
        public String id = "";
        public String fileName = "";
        public long createdAt;
        public String detectedAt = "";
        public long elapsedMillis;
        /** 三态之一：STATE_COMPLETE / STATE_PARTIAL / STATE_UNFINISHED。 */
        public String state = STATE_COMPLETE;
        /** 该态那句话的原文，来自 Report.retrievalReason 或 retrievalPartialReason。 */
        public String reason = "";
        /** 覆盖率小节那一行，与面板同源（{@link #coverageLine}）。 */
        public String coverageNote = "";

        /* ---- 指标卡：四个比率 + 机器生成倾向那一格（它不是比例，所以单列） ---- */
        public double overallRate, excludingCitationsRate, selfWrittenRate, citedDuplicateRate;
        public double aigcScore;
        public String aigcVerdict = "";
        public boolean aigcUnmeasured;

        /* ---- 字符账本：四个比率的分子分母，指标卡的注脚全靠它 ---- */
        public int comparedChars, duplicateChars, citedDuplicateChars, selfWrittenChars, machineChars, excludedChars;
        /** 落盘时账本的闭合残差，恒为 0；留着它是为了让人怀疑"报告是不是凑出来的"时查得出处。 */
        public int ledgerResidual;

        /* ---- 覆盖三态的数字 ---- */
        public int windowsAvailable, windowsPlanned, windowsRetrieved;
        public int coveredChars, comparableChars, comparableCandidates;

        public final ArrayList<String> notes = new ArrayList<String>();
        public int notesTotal;
        public final ArrayList<EngineRow> engines = new ArrayList<EngineRow>();
        public final ArrayList<SourceRow> sources = new ArrayList<SourceRow>();
        public int sourcesTotal;
        public final ArrayList<Evidence> evidence = new ArrayList<Evidence>();
        /** 当时一共有多少处命中；evidence 只装得下前 MAX_EVIDENCE 条时，这个数字才是全貌。 */
        public int evidenceTotal;
        public boolean evidenceTruncated;
        /** AI 判定的分段区间（按正文顺序）。flagged 与其余那档分两种颜色：可疑 / 疑似。 */
        public final ArrayList<AiSegment> aiSegments = new ArrayList<AiSegment>();
        public int aiSegmentsTotal;
        public boolean aiSegmentsTruncated;

        /** 被检正文的长度与指纹：跳转前拿它验一下"现在这篇还是当时那篇吗"。 */
        public int sourceChars;
        public long textDigest;
        public String html = "";
        public boolean htmlCut;
        /** 记录文件落盘后的字节数，由写盘一侧填，不进 JSON（否则字节数自己算自己，永远差几位）。 */
        public int bytes;

        /** 比率类指标只在问出过东西的前提下成立：什么都没查出来时一个数都不许印。 */
        public boolean metricsValid() {
            return !STATE_UNFINISHED.equals(state);
        }

        /** 该条证据还能不能拿去跳正文。 */
        public boolean canJump(Evidence hit) {
            return hit != null && hit.usable(sourceChars);
        }

        /** 规范化：把所有上限在这里收口，之后的 JSON 一律已经在限内。 */
        void normalize() {
            fileName = cut(fileName, MAX_NAME_CHARS);
            detectedAt = cut(detectedAt, 40);
            state = STATE_COMPLETE.equals(state) || STATE_PARTIAL.equals(state) || STATE_UNFINISHED.equals(state)
                    ? state : STATE_COMPLETE;
            reason = cut(reason, MAX_NOTE_CHARS);
            coverageNote = cut(coverageNote, MAX_NOTE_CHARS);
            aigcVerdict = cut(aigcVerdict, MAX_NOTE_CHARS);
            overallRate = rate(overallRate);
            excludingCitationsRate = rate(excludingCitationsRate);
            selfWrittenRate = rate(selfWrittenRate);
            citedDuplicateRate = rate(citedDuplicateRate);
            aigcScore = rate(aigcScore);
            comparedChars = atLeastZero(comparedChars);
            duplicateChars = atLeastZero(duplicateChars);
            citedDuplicateChars = atLeastZero(citedDuplicateChars);
            selfWrittenChars = atLeastZero(selfWrittenChars);
            machineChars = atLeastZero(machineChars);
            excludedChars = atLeastZero(excludedChars);
            windowsAvailable = atLeastZero(windowsAvailable);
            windowsPlanned = atLeastZero(windowsPlanned);
            windowsRetrieved = atLeastZero(windowsRetrieved);
            coveredChars = atLeastZero(coveredChars);
            comparableChars = atLeastZero(comparableChars);
            comparableCandidates = atLeastZero(comparableCandidates);
            sourceChars = atLeastZero(sourceChars);
            elapsedMillis = elapsedMillis < 0 ? 0L : elapsedMillis;

            // 注记：条数与单条长度都夹，砍掉的条数记在 notesTotal 里，界面上说得出"还有几条没显示"。
            notesTotal = Math.max(notesTotal, notes.size());
            while (notes.size() > MAX_NOTES) notes.remove(notes.size() - 1);
            for (int i = 0; i < notes.size(); i++) notes.set(i, cut(notes.get(i), MAX_NOTE_CHARS));

            while (engines.size() > MAX_ENGINE_ROWS) engines.remove(engines.size() - 1);
            for (int i = 0; i < engines.size(); i++) {
                EngineRow row = engines.get(i);
                row.engine = cut(row.engine, 40);
                row.share = rate(row.share);
                row.candidates = atLeastZero(row.candidates);
                row.windows = atLeastZero(row.windows);
            }

            sourcesTotal = Math.max(sourcesTotal, sources.size());
            while (sources.size() > MAX_SOURCE_ROWS) sources.remove(sources.size() - 1);
            for (int i = 0; i < sources.size(); i++) {
                SourceRow row = sources.get(i);
                row.title = cut(row.title, MAX_NAME_CHARS);
                row.authors = cut(row.authors, 80);
                row.year = cut(row.year, 16);
                row.engine = cut(row.engine, 40);
                row.locator = cut(row.locator, 160);
                row.key = cut(row.key, 160);
                row.duplicateChars = atLeastZero(row.duplicateChars);
                row.hitCount = atLeastZero(row.hitCount);
                row.sourceCount = atLeastZero(row.sourceCount);
                row.share = rate(row.share);
            }

            evidenceTotal = Math.max(evidenceTotal, evidence.size());
            // 越界的区间在这一刀就丢掉：留着它，点一下就会跳到别处去，比少一条证据坏得多。
            for (int i = evidence.size() - 1; i >= 0; i--) {
                Evidence hit = evidence.get(i);
                if (hit != null && hit.usable(sourceChars)) continue;
                evidence.remove(i);
                evidenceTruncated = true;
            }
            boolean droppedByCap = evidence.size() > MAX_EVIDENCE;
            while (evidence.size() > MAX_EVIDENCE) evidence.remove(evidence.size() - 1);
            if (droppedByCap) evidenceTruncated = true;
            aiSegmentsTotal = Math.max(aiSegmentsTotal, aiSegments.size());
            for (int i = aiSegments.size() - 1; i >= 0; i--) {
                AiSegment seg = aiSegments.get(i);
                if (seg != null && seg.usable(sourceChars)) continue;
                aiSegments.remove(i);
                aiSegmentsTruncated = true;
            }
            boolean aiCutByCap = aiSegments.size() > MAX_AI_SEGMENTS;
            while (aiSegments.size() > MAX_AI_SEGMENTS) aiSegments.remove(aiSegments.size() - 1);
            if (aiCutByCap) aiSegmentsTruncated = true;
            for (int i = 0; i < aiSegments.size(); i++) {
                AiSegment seg = aiSegments.get(i);
                seg.chars = atLeastZero(seg.chars);
                seg.score = fraction(seg.score);
            }
            java.util.Collections.sort(aiSegments, new java.util.Comparator<AiSegment>() {
                public int compare(AiSegment a, AiSegment b) {
                    return a.start != b.start ? a.start - b.start : a.end - b.end;
                }
            });
            for (int i = 0; i < evidence.size(); i++) {
                Evidence hit = evidence.get(i);
                hit.title = cut(hit.title, MAX_NAME_CHARS);
                hit.engine = cut(hit.engine, 40);
                hit.year = cut(hit.year, 16);
                if (hit.snippet.length() > MAX_SNIPPET_CHARS) {
                    hit.snippet = hit.snippet.substring(0, MAX_SNIPPET_CHARS) + "…";
                    hit.snippetCut = true;
                }
                hit.score = fraction(hit.score);
            }
            String kept = cutHtml(html, MAX_HTML_CHARS);
            if (!kept.equals(html)) htmlCut = true;
            html = kept;
        }

        /* ---- 序列化：写侧一处、读侧一处，字段顺序两边一致，回归才能拿字符串相等当全字段核对 ---- */

        /** 稳定写法：同一个 Record 任何时候都产出同一串 JSON，round-trip 核对就靠它。 */
        public String toJson() {
            StringBuilder out = new StringBuilder(1024);
            out.append("{\"version\":").append(RECORD_VERSION)
                    .append(",\"id\":").append(ApiJson.quote(id))
                    .append(",\"fileName\":").append(ApiJson.quote(fileName))
                    .append(",\"createdAt\":").append(createdAt)
                    .append(",\"detectedAt\":").append(ApiJson.quote(detectedAt))
                    .append(",\"elapsedMillis\":").append(elapsedMillis)
                    .append(",\"state\":").append(ApiJson.quote(state))
                    .append(",\"reason\":").append(ApiJson.quote(reason))
                    .append(",\"coverageNote\":").append(ApiJson.quote(coverageNote))
                    .append(",\"metrics\":{\"overall\":").append(num(overallRate))
                    .append(",\"uncited\":").append(num(excludingCitationsRate))
                    .append(",\"self\":").append(num(selfWrittenRate))
                    .append(",\"cited\":").append(num(citedDuplicateRate))
                    .append(",\"aigcScore\":").append(num(aigcScore))
                    .append(",\"aigcVerdict\":").append(ApiJson.quote(aigcVerdict))
                    .append(",\"aigcUnmeasured\":").append(aigcUnmeasured ? "true" : "false")
                    .append("},\"ledger\":{\"compared\":").append(comparedChars)
                    .append(",\"duplicate\":").append(duplicateChars)
                    .append(",\"cited\":").append(citedDuplicateChars)
                    .append(",\"self\":").append(selfWrittenChars)
                    .append(",\"machine\":").append(machineChars)
                    .append(",\"excluded\":").append(excludedChars)
                    .append(",\"residual\":").append(ledgerResidual)
                    .append("},\"coverage\":{\"windowsAvailable\":").append(windowsAvailable)
                    .append(",\"windowsPlanned\":").append(windowsPlanned)
                    .append(",\"windowsRetrieved\":").append(windowsRetrieved)
                    .append(",\"coveredChars\":").append(coveredChars)
                    .append(",\"comparableChars\":").append(comparableChars)
                    .append(",\"comparableCandidates\":").append(comparableCandidates)
                    .append("},\"notes\":").append(strings(notes))
                    .append(",\"notesTotal\":").append(notesTotal)
                    .append(",\"engines\":[");
            for (int i = 0; i < engines.size(); i++) {
                if (i > 0) out.append(',');
                out.append(engineJson(engines.get(i)));
            }
            out.append("],\"sources\":[");
            for (int i = 0; i < sources.size(); i++) {
                if (i > 0) out.append(',');
                out.append(sourceJson(sources.get(i)));
            }
            out.append("],\"sourcesTotal\":").append(sourcesTotal)
                    .append(",\"evidence\":[");
            for (int i = 0; i < evidence.size(); i++) {
                if (i > 0) out.append(',');
                out.append(evidenceJson(evidence.get(i)));
            }
            out.append("],\"evidenceTotal\":").append(evidenceTotal)
                    .append(",\"evidenceTruncated\":").append(evidenceTruncated ? "true" : "false")
                    .append(",\"ai\":[");
            for (int i = 0; i < aiSegments.size(); i++) {
                if (i > 0) out.append(',');
                AiSegment seg = aiSegments.get(i);
                out.append("{\"start\":").append(seg.start).append(",\"end\":").append(seg.end)
                        .append(",\"chars\":").append(seg.chars).append(",\"score\":").append(num(seg.score))
                        .append(",\"flagged\":").append(seg.flagged ? "true" : "false").append('}');
            }
            out.append("],\"aiTotal\":").append(aiSegmentsTotal)
                    .append(",\"aiTruncated\":").append(aiSegmentsTruncated ? "true" : "false")
                    .append(",\"sourceChars\":").append(sourceChars)
                    .append(",\"textDigest\":").append(textDigest)
                    .append(",\"html\":").append(ApiJson.quote(html))
                    .append(",\"htmlCut\":").append(htmlCut ? "true" : "false")
                    .append('}');
            return out.toString();
        }

        /** 读不回来的记录一律返回 null，让调用方走"按目录重建"，而不是拿半条记录去画报告。 */
        static Record fromJson(Object root) {
            Map<?, ?> map = asMap(root);
            if (map == null || (int) whole(map.get("version")) > RECORD_VERSION) return null;
            Record record = new Record();
            record.id = text(map.get("id"));
            record.fileName = text(map.get("fileName"));
            record.createdAt = whole(map.get("createdAt"));
            record.detectedAt = text(map.get("detectedAt"));
            record.elapsedMillis = whole(map.get("elapsedMillis"));
            record.state = text(map.get("state"));
            record.reason = text(map.get("reason"));
            record.coverageNote = text(map.get("coverageNote"));
            Map<?, ?> metrics = asMap(map.get("metrics"));
            if (metrics != null) {
                record.overallRate = real(metrics.get("overall"));
                record.excludingCitationsRate = real(metrics.get("uncited"));
                record.selfWrittenRate = real(metrics.get("self"));
                record.citedDuplicateRate = real(metrics.get("cited"));
                record.aigcScore = real(metrics.get("aigcScore"));
                record.aigcVerdict = text(metrics.get("aigcVerdict"));
                record.aigcUnmeasured = truth(metrics.get("aigcUnmeasured"));
            }
            Map<?, ?> ledger = asMap(map.get("ledger"));
            if (ledger != null) {
                record.comparedChars = (int) whole(ledger.get("compared"));
                record.duplicateChars = (int) whole(ledger.get("duplicate"));
                record.citedDuplicateChars = (int) whole(ledger.get("cited"));
                record.selfWrittenChars = (int) whole(ledger.get("self"));
                record.machineChars = (int) whole(ledger.get("machine"));
                record.excludedChars = (int) whole(ledger.get("excluded"));
                record.ledgerResidual = (int) whole(ledger.get("residual"));
            }
            Map<?, ?> coverage = asMap(map.get("coverage"));
            if (coverage != null) {
                record.windowsAvailable = (int) whole(coverage.get("windowsAvailable"));
                record.windowsPlanned = (int) whole(coverage.get("windowsPlanned"));
                record.windowsRetrieved = (int) whole(coverage.get("windowsRetrieved"));
                record.coveredChars = (int) whole(coverage.get("coveredChars"));
                record.comparableChars = (int) whole(coverage.get("comparableChars"));
                record.comparableCandidates = (int) whole(coverage.get("comparableCandidates"));
            }
            for (Object item : asList(map.get("notes"))) record.notes.add(text(item));
            record.notesTotal = (int) whole(map.get("notesTotal"));
            for (Object item : asList(map.get("engines"))) {
                EngineRow row = engineFrom(item);
                if (row != null) record.engines.add(row);
            }
            for (Object item : asList(map.get("sources"))) {
                SourceRow row = sourceFrom(item);
                if (row != null) record.sources.add(row);
            }
            record.sourcesTotal = (int) whole(map.get("sourcesTotal"));
            for (Object item : asList(map.get("evidence"))) {
                Evidence hit = evidenceFrom(item);
                if (hit != null) record.evidence.add(hit);
            }
            record.evidenceTotal = (int) whole(map.get("evidenceTotal"));
            record.evidenceTruncated = truth(map.get("evidenceTruncated"));
            for (Object item : asList(map.get("ai"))) {
                Map<?, ?> seg = asMap(item);
                if (seg == null) continue;
                AiSegment row = new AiSegment();
                row.start = (int) whole(seg.get("start"));
                row.end = (int) whole(seg.get("end"));
                row.chars = (int) whole(seg.get("chars"));
                row.score = real(seg.get("score"));
                row.flagged = truth(seg.get("flagged"));
                record.aiSegments.add(row);
            }
            record.aiSegmentsTotal = (int) whole(map.get("aiTotal"));
            record.aiSegmentsTruncated = truth(map.get("aiTruncated"));
            record.sourceChars = (int) whole(map.get("sourceChars"));
            record.textDigest = whole(map.get("textDigest"));
            record.html = text(map.get("html"));
            record.htmlCut = truth(map.get("htmlCut"));
            return record;
        }

        /** 写盘前的同一份规范化：改字段就地生效，返回自己只为链式读着顺。 */
        public Record normalized() {
            normalize();
            return this;
        }

        /** 深拷贝：写盘一侧要砍字段，不能把调用方内存里那份一起削了。 */
        Record copy() {
            Record copy = fromJson(ApiJson.parse(toJson()));
            copy.bytes = bytes;
            return copy;
        }
    }

    /* ---- 三个行对象的 JSON：行对象保持 LocalLibrary.Entry 那种无行为的哑数据，写法收在这一个地方 ---- */

    static String engineJson(EngineRow row) {
        return "{\"engine\":" + ApiJson.quote(row.engine)
                + ",\"share\":" + num(row.share)
                + ",\"candidates\":" + row.candidates
                + ",\"windows\":" + row.windows + "}";
    }

    static EngineRow engineFrom(Object item) {
        Map<?, ?> map = asMap(item);
        if (map == null) return null;
        EngineRow row = new EngineRow();
        row.engine = text(map.get("engine"));
        row.share = real(map.get("share"));
        row.candidates = (int) whole(map.get("candidates"));
        row.windows = (int) whole(map.get("windows"));
        return row;
    }

    static String sourceJson(SourceRow row) {
        return "{\"key\":" + ApiJson.quote(row.key)
                + ",\"title\":" + ApiJson.quote(row.title)
                + ",\"authors\":" + ApiJson.quote(row.authors)
                + ",\"year\":" + ApiJson.quote(row.year)
                + ",\"engine\":" + ApiJson.quote(row.engine)
                + ",\"locator\":" + ApiJson.quote(row.locator)
                + ",\"duplicateChars\":" + row.duplicateChars
                + ",\"hitCount\":" + row.hitCount
                + ",\"sourceCount\":" + row.sourceCount
                + ",\"firstStart\":" + row.firstStart
                + ",\"share\":" + num(row.share)
                + ",\"others\":" + (row.others ? "true" : "false")
                + ",\"othersCount\":" + row.othersCount + "}";
    }

    static SourceRow sourceFrom(Object item) {
        Map<?, ?> map = asMap(item);
        if (map == null) return null;
        SourceRow row = new SourceRow();
        row.key = text(map.get("key"));
        row.title = text(map.get("title"));
        row.authors = text(map.get("authors"));
        row.year = text(map.get("year"));
        row.engine = text(map.get("engine"));
        row.locator = text(map.get("locator"));
        row.duplicateChars = (int) whole(map.get("duplicateChars"));
        row.hitCount = (int) whole(map.get("hitCount"));
        row.sourceCount = (int) whole(map.get("sourceCount"));
        // firstStart 的"没有命中"是 -1，缺键时也必须读回 -1，不然空行会被当成能跳的。
        row.firstStart = map.get("firstStart") == null ? -1 : (int) whole(map.get("firstStart"));
        row.share = real(map.get("share"));
        row.others = truth(map.get("others"));
        row.othersCount = (int) whole(map.get("othersCount"));
        return row;
    }

    static String evidenceJson(Evidence hit) {
        return "{\"start\":" + hit.start
                + ",\"end\":" + hit.end
                + ",\"score\":" + num(hit.score)
                + ",\"chan\":" + hit.channel
                + ",\"title\":" + ApiJson.quote(hit.title)
                + ",\"engine\":" + ApiJson.quote(hit.engine)
                + ",\"year\":" + ApiJson.quote(hit.year)
                + ",\"snippet\":" + ApiJson.quote(hit.snippet)
                + ",\"cut\":" + (hit.snippetCut ? "true" : "false") + "}";
    }

    static Evidence evidenceFrom(Object item) {
        Map<?, ?> map = asMap(item);
        if (map == null) return null;
        Evidence hit = new Evidence();
        hit.start = (int) whole(map.get("start"));
        hit.end = (int) whole(map.get("end"));
        hit.score = real(map.get("score"));
        // 老库里没有 chan 这一位：缺就是 0，也就是字面证据——不许把老报告的颜色猜成"改写"。
        hit.channel = (int) whole(map.get("chan"));
        hit.title = text(map.get("title"));
        hit.engine = text(map.get("engine"));
        hit.year = text(map.get("year"));
        hit.snippet = text(map.get("snippet"));
        hit.snippetCut = truth(map.get("cut"));
        return hit;
    }

    /* ---- index.json：只有列表页要读的那几列 ---- */

    static String summaryJson(Summary summary) {
        return "{\"id\":" + ApiJson.quote(summary.id)
                + ",\"fileName\":" + ApiJson.quote(summary.fileName)
                + ",\"detectedAt\":" + ApiJson.quote(summary.detectedAt)
                + ",\"state\":" + ApiJson.quote(summary.state)
                + ",\"reason\":" + ApiJson.quote(summary.reason)
                + ",\"createdAt\":" + summary.createdAt
                + ",\"elapsedMillis\":" + summary.elapsedMillis
                + ",\"overallRate\":" + num(summary.overallRate)
                + ",\"comparedChars\":" + summary.comparedChars
                + ",\"duplicateChars\":" + summary.duplicateChars
                + ",\"evidenceTotal\":" + summary.evidenceTotal
                + ",\"bytes\":" + summary.bytes
                + ",\"truncated\":" + (summary.truncated ? "true" : "false") + "}";
    }

    static Summary summaryFrom(Object item) {
        Map<?, ?> map = asMap(item);
        if (map == null) return null;
        Summary summary = new Summary();
        summary.id = text(map.get("id"));
        summary.fileName = text(map.get("fileName"));
        summary.detectedAt = text(map.get("detectedAt"));
        summary.state = text(map.get("state"));
        summary.reason = text(map.get("reason"));
        summary.createdAt = whole(map.get("createdAt"));
        summary.elapsedMillis = whole(map.get("elapsedMillis"));
        summary.overallRate = real(map.get("overallRate"));
        summary.comparedChars = (int) whole(map.get("comparedChars"));
        summary.duplicateChars = (int) whole(map.get("duplicateChars"));
        summary.evidenceTotal = (int) whole(map.get("evidenceTotal"));
        summary.bytes = (int) whole(map.get("bytes"));
        summary.truncated = truth(map.get("truncated"));
        return summary.id.length() == 0 ? null : summary;
    }

    static String indexJson(ArrayList<Summary> list) {
        StringBuilder out = new StringBuilder(64 + list.size() * 200);
        out.append("{\"version\":").append(RECORD_VERSION).append(",\"records\":[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) out.append(',');
            out.append(summaryJson(list.get(i)));
        }
        return out.append("]}").toString();
    }

    /** 覆盖率那一行的唯一写法：面板与详情页读同一句，两处不许说法不一。 */
    public static String coverageLine(int windowsRetrieved, int windowsAvailable,
                                      int coveredChars, int comparableChars, boolean partial) {
        String line = "检索覆盖 " + windowsRetrieved + "/" + windowsAvailable + " 窗口";
        line = line + " · " + coveredChars + "/" + comparableChars + " 字";
        if (partial) line = line + " · 相似率是下限";
        return line;
    }

    /** FNV-1a 64：跳正文前验一下"现在这篇还是当时检的那篇吗"，不需要密码学强度，只要能挡住改过一个字。 */
    public static long digest(String text) {
        long hash = 0xcbf29ce484222325L;
        String value = text == null ? "" : text;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /**
     * 从一次真跑完的检查生成记录。选哪些字段只看一件事——重画这张报告需要什么：
     * 指标卡要四个比率和它们的分子分母，覆盖小节要三态与那几组数，来源分布要两类行，
     * 证据表要区间与出处，导出要 HTML。比对基线（{@code Report.baseline}）整份语料不收。
     */
    public static Record recordFor(DuplicateEngine.Report report, String fileName) {
        Record record = new Record();
        if (report == null) return record;
        record.fileName = fileName == null ? "" : fileName;
        record.createdAt = System.currentTimeMillis();
        record.detectedAt = report.detectedAt;
        record.elapsedMillis = report.elapsedMillis;
        CharLedger.Balance balance = report.ledger;
        record.comparedChars = report.comparedChars;
        record.duplicateChars = report.duplicateChars;
        record.citedDuplicateChars = report.citedDuplicateChars;
        // 账本缺席只可能发生在手拼的报告里，那种场合退到比对侧自己数的字数，口径与 0.7.1 之前一致。
        record.selfWrittenChars = balance == null
                ? report.comparedChars - report.duplicateChars : balance.selfWrittenChars;
        record.machineChars = balance == null ? 0 : balance.machineChars;
        record.excludedChars = report.excludedChars;
        record.ledgerResidual = balance == null ? 0 : balance.residual();
        record.overallRate = report.overallRate;
        record.excludingCitationsRate = report.excludingCitationsRate;
        record.selfWrittenRate = report.selfWrittenRate;
        record.citedDuplicateRate = balance == null ? 0d : balance.citedDuplicateRate;
        // 机器生成倾向那一格不是比例：档位与均分各自存一份，界面上不许出现百分号。
        record.aigcScore = report.aigcRate;
        record.aigcVerdict = DuplicateEngine.aigcTrend(report);
        record.aigcUnmeasured = DuplicateEngine.aigcUnmeasured(report);
        record.state = report.retrievalIncomplete ? STATE_UNFINISHED
                : (report.retrievalPartial ? STATE_PARTIAL : STATE_COMPLETE);
        String reason = STATE_UNFINISHED.equals(record.state) ? report.retrievalReason : report.retrievalPartialReason;
        record.reason = reason == null ? "" : reason;
        record.windowsAvailable = report.windowsAvailable;
        record.windowsPlanned = report.windowsPlanned;
        record.windowsRetrieved = report.windowsRetrieved;
        record.coveredChars = report.coveredChars;
        record.comparableChars = report.comparableChars;
        record.comparableCandidates = report.comparableCandidates;
        record.coverageNote = coverageLine(report.windowsRetrieved, report.windowsAvailable,
                report.coveredChars, report.comparableChars, report.retrievalPartial);
        for (int i = 0; i < report.notes.size(); i++) record.notes.add(report.notes.get(i));
        record.notesTotal = report.notes.size();
        // 「按检索源分布」的三张表：键的并集按 byEngine 先、candidateCount 补、windowsAsked 补，与 CheckReport 同一走法。
        ArrayList<String> keys = new ArrayList<String>();
        for (String key : report.byEngine.keySet()) if (!keys.contains(key)) keys.add(key);
        for (String key : report.candidateCount.keySet()) if (!keys.contains(key)) keys.add(key);
        for (String key : report.windowsAsked.keySet()) if (!keys.contains(key)) keys.add(key);
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            EngineRow row = new EngineRow();
            row.engine = key;
            Double share = report.byEngine.get(key);
            Integer candidates = report.candidateCount.get(key);
            Integer asked = report.windowsAsked.get(key);
            row.share = share == null ? 0d : share.doubleValue();
            row.candidates = candidates == null ? 0 : candidates.intValue();
            row.windows = asked == null ? 0 : asked.intValue();
            record.engines.add(row);
        }
        String norm = TextCorpus.normalize(report.sourceText);
        SourceLedger ledger = SourceLedger.aggregate(report.hits, norm, record.comparedChars);
        record.sourcesTotal = ledger.rows.size();
        for (int i = 0; i < ledger.rows.size() && i < MAX_SOURCE_ROWS; i++) {
            SourceLedger.Row source = ledger.rows.get(i);
            SourceRow row = new SourceRow();
            row.key = source.key;
            row.title = source.title;
            row.authors = source.authors;
            row.year = source.year;
            row.engine = source.engine;
            row.locator = source.locator;
            row.duplicateChars = source.duplicateChars;
            row.hitCount = source.hitCount;
            row.sourceCount = source.sourceCount;
            row.firstStart = source.firstStart;
            row.share = source.share(record.comparedChars);
            row.others = source.others;
            row.othersCount = source.othersCount;
            record.sources.add(row);
        }
        // 证据按正文顺序取前 MAX_EVIDENCE 条：跳正文要的是位置，位置在前面的先能点得着。
        record.evidenceTotal = report.hits.size();
        String source = report.sourceText == null ? "" : report.sourceText;
        for (int i = 0; i < report.hits.size() && record.evidence.size() < MAX_EVIDENCE; i++) {
            TextCorpus.Hit hit = report.hits.get(i);
            if (hit == null) continue;
            Evidence row = new Evidence();
            row.start = hit.start;
            row.end = hit.end;
            row.score = hit.score;
            row.channel = hit.channel;
            row.title = hit.source == null ? "" : hit.source.title;
            row.engine = hit.source == null ? "" : hit.source.engine;
            row.year = hit.source == null ? "" : hit.source.year;
            // 这里只粗切到四倍宽，最后一刀交给 normalize：省略号加在哪儿只能有一个地方说了算。
            int stop = Math.min(hit.end, Math.min(source.length(), hit.start + 4 * MAX_SNIPPET_CHARS));
            row.snippet = hit.start >= 0 && hit.end > hit.start && hit.start <= stop
                    ? source.substring(hit.start, stop) : "";
            record.evidence.add(row);
        }
        record.evidenceTruncated = report.hits.size() > record.evidence.size();
        // AI 分段存区间而不是只存一个总数：命中地图要的是"可疑的那几段在正文哪一处"。
        record.aiSegmentsTotal = report.aigc == null ? 0 : report.aigc.segments.size();
        if (report.aigc != null) {
            for (int i = 0; i < report.aigc.segments.size() && record.aiSegments.size() < MAX_AI_SEGMENTS; i++) {
                AigcDetector.Segment seg = report.aigc.segments.get(i);
                if (seg == null) continue;
                AiSegment row = new AiSegment();
                row.start = seg.start;
                row.end = seg.end;
                row.chars = seg.chars;
                row.score = seg.score;
                row.flagged = seg.flagged;
                record.aiSegments.add(row);
            }
        }
        record.aiSegmentsTruncated = record.aiSegmentsTotal > record.aiSegments.size();
        record.sourceChars = source.length();
        record.textDigest = digest(source);
        record.html = CheckReport.html(record.fileName, report);
        return record.normalized();
    }

    /* ================= 库本身 ================= */

    private final File directory;
    private final ArrayList<Summary> summaries = new ArrayList<Summary>();
    private boolean loaded;
    private String lastError = "";

    /** 只接 File：调用方给应用私有目录，本类不碰 Context，与 LocalLibrary 同一条约束。 */
    public ReportStore(File directory) {
        this.directory = directory == null ? new File(".") : directory;
    }

    public File directoryFile() {
        return directory;
    }

    public String lastError() {
        return lastError;
    }

    /** 历史列表，新的在前；返回副本，调用方改了不会动到库里。 */
    public ArrayList<Summary> history() {
        load();
        sortNewestFirst();
        return new ArrayList<Summary>(summaries);
    }

    public int size() {
        load();
        return summaries.size();
    }

    /** 库里全部记录文件的实测字节总数，体积封顶拿它说话。 */
    public int totalBytes() {
        load();
        int total = 0;
        for (int i = 0; i < summaries.size(); i++) total += summaries.get(i).bytes;
        return total;
    }

    public int indexBytes() {
        load();
        return bytesOf(indexJson(summaries));
    }

    /** 读一条完整记录；不存在或已损坏返回 null，原因读 lastError()。 */
    public Record record(String id) {
        load();
        File file = fileFor(id);
        if (file == null || !file.isFile()) {
            lastError = "报告记录不存在";
            return null;
        }
        Record record;
        try {
            record = Record.fromJson(ApiJson.parse(new String(readBytes(file), "UTF-8")));
        } catch (Exception error) {
            lastError = "报告记录已损坏";
            return null;
        }
        if (record == null) {
            lastError = "报告记录的版本比本机构还新";
            return null;
        }
        record.bytes = (int) file.length();
        lastError = "";
        return record;
    }

    /**
     * 保存一条记录，返回真正落下来的那一份（可能被体积上限砍过），失败返回 null。
     * 为什么不改调用方传进来的那份：调用方手里那份还挂在界面内存上，砍它就等于屏幕上
     * 出现一份"看起来存下来了、其实盘上没有"的报告。
     */
    public Record save(Record input) {
        load();
        if (input == null) {
            lastError = "没有要保存的报告";
            return null;
        }
        if (input.fileName.trim().length() == 0) {
            lastError = "报告没有文件名，列表里会认不出是哪篇";
            return null;
        }
        if (!directory.isDirectory() && !directory.mkdirs()) {
            lastError = "无法创建报告目录";
            return null;
        }
        Record stored = input.copy().normalized();
        if (stored.createdAt <= 0L) stored.createdAt = System.currentTimeMillis();
        stored.id = newId(stored);
        File file = fileFor(stored.id);
        if (file == null) {
            lastError = "报告编号越出目录";
            return null;
        }
        if (!shrinkToFit(stored)) {
            lastError = "单条报告超过 " + MAX_RECORD_BYTES + " 字节上限，连一条证据都装不下";
            return null;
        }
        try {
            FileOutputStream out = null;
            try {
                out = new FileOutputStream(file);
                out.write(jsonBytes(stored));
            } finally {
                close(out);
            }
        } catch (IOException error) {
            lastError = "写入失败";
            return null;
        }
        Summary summary = Summary.of(stored);
        for (int i = summaries.size() - 1; i >= 0; i--) {
            if (summaries.get(i).id.equals(summary.id)) summaries.remove(i);
        }
        summaries.add(summary);
        sortNewestFirst();
        evictByCount();
        evictForIndexBudget();
        persistIndex();
        lastError = "";
        return stored;
    }

    /** 删一条记录与它的索引行，成功返回 true。 */
    public boolean delete(String id) {
        load();
        File file = fileFor(id);
        if (file == null || !file.isFile() || !file.delete()) return false;
        for (int i = summaries.size() - 1; i >= 0; i--) {
            if (summaries.get(i).id.equals(sanitizeId(id))) summaries.remove(i);
        }
        persistIndex();
        return true;
    }

    public void clear() {
        load();
        for (int i = 0; i < summaries.size(); i++) deleteFile(summaries.get(i).id);
        summaries.clear();
        persistIndex();
    }

    /**
     * 体积封顶：先丢 HTML（详情页不靠它渲染，导出时才用），再丢证据表的尾巴，
     * 一条证据都不剩就交回 false 让调用方拒收——写半条报告比不写更坏。
     */
    private boolean shrinkToFit(Record record) {
        while (bytesOf(record.toJson()) > MAX_RECORD_BYTES) {
            if (record.html.length() > 0) {
                record.html = "";
                record.htmlCut = true;
                continue;
            }
            if (record.evidence.size() > 1) {
                record.evidence.remove(record.evidence.size() - 1);
                record.evidenceTruncated = true;
                continue;
            }
            return false;
        }
        record.bytes = bytesOf(record.toJson());
        return true;
    }

    /** 超条数先删最旧的：新记录绝不能被自己的第一条挤掉。 */
    private void evictByCount() {
        while (summaries.size() > MAX_RECORDS) {
            Summary oldest = summaries.remove(summaries.size() - 1);
            deleteFile(oldest.id);
        }
    }

    /** 索引本身也要封顶。摘要已经很小，真要撞上来只能再往外扔最旧的记录，不留一个读不动的索引。 */
    private void evictForIndexBudget() {
        while (summaries.size() > 1 && bytesOf(indexJson(summaries)) > MAX_INDEX_BYTES) {
            Summary oldest = summaries.remove(summaries.size() - 1);
            deleteFile(oldest.id);
        }
    }

    /** 编号由时间与文件名摘要决定，同一毫秒的重名往后加序号；字符集限死 [a-z0-9-]，拼出来的文件名一定安全。 */
    private String newId(Record record) {
        long stamp = record.createdAt > 0L ? record.createdAt : System.currentTimeMillis();
        String base = "r" + stamp + "-" + Long.toHexString((digest(record.fileName) & Long.MAX_VALUE) % 0x1000000L);
        String candidate = base;
        int tail = 2;
        while (taken(candidate)) {
            candidate = base + "-" + tail;
            tail++;
            if (tail > 999) break;
        }
        return candidate;
    }

    private boolean taken(String id) {
        for (int i = 0; i < summaries.size(); i++) if (summaries.get(i).id.equals(id)) return true;
        File file = fileFor(id);
        return file != null && file.exists();
    }

    /** 只在第一次用到时读盘；索引坏了不抛错，退到按目录重建。 */
    private void load() {
        if (loaded) return;
        loaded = true;
        summaries.clear();
        File index = new File(directory, INDEX_NAME);
        if (index.isFile()) {
            try {
                Object root = ApiJson.parse(new String(readBytes(index), "UTF-8"));
                Map<?, ?> map = asMap(root);
                List<?> items = map == null ? null : asList(map.get("records"));
                if (map != null && (int) whole(map.get("version")) <= RECORD_VERSION && items != null) {
                    for (int i = 0; i < items.size(); i++) {
                        Summary summary = summaryFrom(items.get(i));
                        // 索引里点了头但文件没了的行要当没有：列表里点出一条空报告比少一条历史记录坏得多。
                        if (summary == null) continue;
                        File file = fileFor(summary.id);
                        if (file == null || !file.isFile()) continue;
                        summaries.add(summary);
                    }
                    if (!summaries.isEmpty()) {
                        sortNewestFirst();
                        return;
                    }
                }
            } catch (Exception error) {
                // 索引写坏（掉电留下半截 JSON 是常态）：丢掉索引行，下面按目录重建。
                summaries.clear();
            }
        }
        rebuildFromDirectory();
    }

    /** 索引缺失或损坏时按目录里的记录文件重建；单条坏了只丢那一条，其余照常列得出来。 */
    private void rebuildFromDirectory() {
        File[] files = directory.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
            public int compare(File left, File right) {
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (file == null || !file.isFile()) continue;
            String name = file.getName();
            if (INDEX_NAME.equalsIgnoreCase(name)) continue;
            if (!name.toLowerCase(java.util.Locale.US).endsWith(RECORD_SUFFIX)) continue;
            String id = sanitizeId(name.substring(0, name.length() - RECORD_SUFFIX.length()));
            if (id.length() == 0) continue;
            try {
                Record record = Record.fromJson(ApiJson.parse(new String(readBytes(file), "UTF-8")));
                if (record == null) continue;
                record.id = id;
                record.bytes = (int) file.length();
                Summary summary = Summary.of(record);
                summary.id = id;
                summaries.add(summary);
            } catch (Exception error) {
                // 这条记录本身坏了：跳过它，别让它连累其余几十条。
            }
        }
        sortNewestFirst();
        if (!summaries.isEmpty()) persistIndex();
    }

    private void sortNewestFirst() {
        // 同一毫秒里存了两条（批量导入历史）时按编号定序，两次运行列表顺序必须逐字符一样。
        java.util.Collections.sort(summaries, new java.util.Comparator<Summary>() {
            public int compare(Summary left, Summary right) {
                if (left.createdAt != right.createdAt) return left.createdAt < right.createdAt ? 1 : -1;
                return right.id.compareTo(left.id);
            }
        });
    }

    private void persistIndex() {
        if (!directory.isDirectory() && !directory.mkdirs()) return;
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(directory, INDEX_NAME));
            out.write(bytes(indexJson(summaries)));
        } catch (IOException error) {
            // 写不进磁盘时保留内存状态；读取端有按目录重建兜底，和 LocalLibrary 同一套退路。
        } finally {
            close(out);
        }
    }

    private void deleteFile(String id) {
        File file = fileFor(id);
        if (file != null && file.isFile()) file.delete();
    }

    /** 目录内的记录文件；编号越界返回 null（LocalLibrary.place 那套越界检查照抄）。 */
    private File fileFor(String id) {
        String safe = sanitizeId(id);
        if (safe.length() == 0) return null;
        return place(safe + RECORD_SUFFIX);
    }

    private File place(String name) {
        if (name == null || name.length() == 0) return null;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return null;
        if (INDEX_NAME.equalsIgnoreCase(name)) return null;
        File file = new File(directory, name);
        try {
            String root = directory.getCanonicalPath();
            String path = file.getCanonicalPath();
            if (!path.startsWith(root + File.separator) && !path.equals(root)) return null;
        } catch (IOException error) {
            return null;
        }
        return file;
    }

    /** 编号只留 [a-z0-9-]：编号会直接拼成文件名，任何别的字符都是给文件系统添意外。 */
    static String sanitizeId(String id) {
        if (id == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < id.length() && out.length() < 96; i++) {
            char c = id.charAt(i);
            if (c >= 'a' && c <= 'z') out.append(c);
            else if (c >= 'A' && c <= 'Z') out.append(Character.toLowerCase(c));
            else if (c >= '0' && c <= '9' || c == '-') out.append(c);
        }
        return out.toString();
    }

    /* ================= 值域收口与序列化小工具 ================= */

    /** 比率一律落在 0-100：NaN/Infinity 会写进 JSON 却让 ApiJson 直接判格式无效，一条记录都读不回来。 */
    static double rate(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0d;
        return value < 0d ? 0d : (value > 100d ? 100d : value);
    }

    /** 相似度分是 0-1 的分数，不是百分比：拿 rate() 夹会放行 100，一条 100 分的命中能凭空冒出来。 */
    static double fraction(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0d;
        return value < 0d ? 0d : (value > 1d ? 1d : value);
    }

    private static int atLeastZero(int value) {
        return value < 0 ? 0 : value;
    }

    private static String num(double value) {
        return Double.toString(rate(value));
    }

    static String cut(String value, int limit) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit).trim();
    }

    /**
     * HTML 只能按段落边界砍。从中间硬截会留下半个标签，浏览器能把后面整张表吞掉，
     * 导出打开是一张白板；找不到段落边界才硬切，并在末尾留下这句截断声明。
     */
    static String cutHtml(String html, int limit) {
        String value = html == null ? "" : html;
        if (value.length() <= limit) return value;
        String marker = "<p><em>报告过长，此处按上限截断。</em></p>";
        if (limit <= marker.length()) return value.substring(0, Math.max(0, limit));
        // 先给声明留出位子，再在剩下的预算里找最后一段的收尾：宁可少留几段，也不写半个标签出去。
        int budget = limit - marker.length();
        int edge = value.lastIndexOf("</p>", budget);
        int keep = edge >= 0 && edge + 4 <= budget ? edge + 4 : budget;
        return value.substring(0, keep) + marker;
    }

    private static String strings(ArrayList<String> values) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            out.append(ApiJson.quote(values.get(i)));
        }
        return out.append(']').toString();
    }

    private static byte[] jsonBytes(Record record) {
        return bytes(record.toJson());
    }

    static int bytesOf(String value) {
        return bytes(value).length;
    }

    private static byte[] bytes(String value) {
        try {
            return value.getBytes("UTF-8");
        } catch (IOException error) {
            throw new IllegalStateException("UTF-8 一定存在");
        }
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : null;
    }

    private static List<?> asList(Object value) {
        return value instanceof List ? (List<?>) value : java.util.Collections.emptyList();
    }

    private static String text(Object value) {
        return value instanceof String ? (String) value : "";
    }

    private static boolean truth(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static long whole(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static double real(Object value) {
        return value instanceof Number ? rate(((Number) value).doubleValue()) : 0d;
    }

    private static byte[] readBytes(File file) throws IOException {
        long length = file.length();
        // 读之前先挡一道：盘上出现比上限还大的文件，说明有别的东西写进了这个目录，宁可报错也别撑爆内存。
        if (length > MAX_RECORD_BYTES * 4L) throw new IOException("记录文件超过上限");
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(length > 0 ? (int) length : 4096);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 关闭失败不影响已经写入的内容
        }
    }
}

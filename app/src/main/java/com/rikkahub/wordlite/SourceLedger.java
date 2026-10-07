package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;

/**
 * 命中按文献聚合的来源榜：每篇文献一个重复字符数、一处处数、一处最长命中、并了几条题录。
 * 纯函数——不检索、不碰界面，输入只有 report.hits、TextCorpus.normalize(被检原文) 与 comparedChars。
 *
 * 为什么能从 hits 反推，而不必在 TextCorpus 里边匹配边记账：句级与指纹带两条命中路径都是
 * "一个区间一条 Hit、一份有效字符"（flush 与 addBandHits 各 push 一条命中、各记一次同一区间的分子），
 * 所以 Σ 各行 duplicateChars == Report.duplicateChars 是一条真不变量。放在报告侧算反而能当交叉验证：
 * 谁往 hits 里多塞一条命中却少记一份分子，这里就会断，边匹配边累加则是同一段代码自证。
 *
 * 键只有一套：DOI 键与题名指纹键都问 CandidateRanker 要（doiOf / titleKey / MIN_TITLE_KEY），
 * 合条条件连"两边都有 DOI 时 DOI 必须相同"那条否决一起照抄 dedup 的桶逻辑，本类一个字都不解析 DOI。
 */
public final class SourceLedger {
    /** 榜单最多列几篇，第 13 篇起折成"其余 N 篇合计"；与 PaperSources.Limits.perEngine 同量级，一屏能扫完。 */
    public static final int MAX_ROWS = 12;

    /** 来源榜的一行：折叠前是一篇文献，折叠后是被折掉那几篇的合计（others）。 */
    public static final class Row {
        /** 排序决胜用的稳定键：DOI 优先，退到题名指纹，两者都没有才用桶序号。 */
        public String key = "";
        public String doi = "", title = "", authors = "", year = "", locator = "";
        /** 检索源标签，多个用 " + " 连接：并了两条题录这件事本身就是用户该知道的证据。 */
        public String engine = "";
        /** 该篇在每个检索源名下各记了多少字符，两个列表一一对应，与「按检索源分布」那张表对照。 */
        public final ArrayList<String> engineKeys = new ArrayList<String>();
        public final ArrayList<Integer> engineChars = new ArrayList<Integer>();
        /** 与 Report.duplicateChars 同口径：各行相加（含 others）等于总重复字符数。 */
        public int duplicateChars;
        public int hitCount;
        /** 这一行背后并了几个 Source 对象，跨检索源归并的证据。 */
        public int sourceCount;
        /** 最早命中的偏移，1.0.0 的"点证据跳正文"要用。 */
        public int firstStart = -1;
        /** 最长那一处命中，用作命中样例。 */
        public int longestStart, longestEnd;
        public float topScore;
        public boolean others;
        /** others 那一行折掉了几个 Source 桶。 */
        public int othersCount;

        /** 该篇重复率 = 该篇重复字符 / 整篇有效字数。分母统一，各行相加才等于总相似度比。 */
        public double share(int total) {
            if (total <= 0 || duplicateChars <= 0) return 0d;
            return duplicateChars * 100d / total;
        }
    }

    public final ArrayList<Row> rows = new ArrayList<Row>();
    /** 全部行之和（含 others），恒等于 Report.duplicateChars。 */
    public int duplicateChars;
    public int comparedChars;
    /** 折叠之前的真实篇数：表里只画 MAX_ROWS + 1 行，标题上要说清一共几篇。 */
    public int paperCount;

    private SourceLedger() { }

    /** 一个桶 = 一篇文献。两个键都登记，与 CandidateRanker.dedup 的 byDoi/byTitle 同构。 */
    private static final class Bucket {
        final Row row = new Row();
        String doi = "", title = "";
        int sources;
    }

    /**
     * 按篇聚合。norm 必须是 TextCorpus.normalize(被检原文)：有效字符按它数，口径才和分子一致。
     * hits 为 null 或空时返回空账本，不抛。
     */
    public static SourceLedger aggregate(ArrayList<TextCorpus.Hit> hits, String norm, int comparedChars) {
        SourceLedger ledger = new SourceLedger();
        ledger.comparedChars = comparedChars < 0 ? 0 : comparedChars;
        if (hits == null || hits.isEmpty()) return ledger;
        String folded = norm == null ? "" : norm;
        // owner 只是"这个 Source 对象算过账没有"的备忘（Source 没有值语义，按对象身份就够）。
        // 真正的合并键在 assign 里问 CandidateRanker 要——只按对象身份分桶会把"同一篇从两个检索源
        // 各回来一次"画成两行，那正是这张表最该避免的样子。
        LinkedHashMap<TextCorpus.Source, Bucket> owner = new LinkedHashMap<TextCorpus.Source, Bucket>();
        LinkedHashMap<String, Bucket> byDoi = new LinkedHashMap<String, Bucket>();
        LinkedHashMap<String, Bucket> byTitle = new LinkedHashMap<String, Bucket>();
        ArrayList<Bucket> buckets = new ArrayList<Bucket>();
        TextCorpus.Source anonymous = new TextCorpus.Source();    // 没带来源的命中全并到一行，不至于一条命中一行
        for (int i = 0; i < hits.size(); i++) {
            TextCorpus.Hit hit = hits.get(i);
            if (hit == null) continue;
            TextCorpus.Source source = hit.source == null ? anonymous : hit.source;
            Bucket bucket = owner.get(source);
            if (bucket == null) {
                bucket = assign(source, byDoi, byTitle, buckets);
                owner.put(source, bucket);
            }
            charge(bucket, source, hit, folded);
        }
        for (int i = 0; i < buckets.size(); i++) {
            Bucket bucket = buckets.get(i);
            bucket.row.sourceCount = bucket.sources;
            bucket.row.engine = labels(bucket.row.engineKeys);
            ledger.rows.add(bucket.row);
        }
        ledger.paperCount = buckets.size();
        Collections.sort(ledger.rows, new Comparator<Row>() {
            public int compare(Row left, Row right) {
                if (left.duplicateChars != right.duplicateChars)
                    return left.duplicateChars > right.duplicateChars ? -1 : 1;
                int a = left.firstStart < 0 ? Integer.MAX_VALUE : left.firstStart;
                int b = right.firstStart < 0 ? Integer.MAX_VALUE : right.firstStart;
                if (a != b) return a < b ? -1 : 1;
                return left.key.compareTo(right.key);
            }
        });
        ledger.duplicateChars = sum(ledger.rows);
        fold(ledger);
        return ledger;
    }

    /** 某一检索源名下贡献了多少重复字符，与 Report.byEngine 对照用；空引擎按 TextCorpus 的 "local" 计。 */
    public int duplicateCharsFor(String engine) {
        String wanted = engineKey(engine);
        int total = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            for (int e = 0; e < row.engineKeys.size(); e++)
                if (row.engineKeys.get(e).equals(wanted)) total += row.engineChars.get(e).intValue();
        }
        return total;
    }

    /**
     * 给一个 Source 找桶，规则照抄 CandidateRanker.dedup：DOI 键优先，题名键兜底，
     * 两边都有 DOI 且不同时否决——同题不同篇不许并条。桶内展示字段只补空、绝不覆盖，同 adopt。
     */
    private static Bucket assign(TextCorpus.Source source, LinkedHashMap<String, Bucket> byDoi,
                                 LinkedHashMap<String, Bucket> byTitle, ArrayList<Bucket> buckets) {
        String doi = CandidateRanker.doiOf(source);
        String title = CandidateRanker.titleKey(source.title);
        Bucket bucket = doi.isEmpty() ? null : byDoi.get(doi);
        if (bucket == null && title.length() >= CandidateRanker.MIN_TITLE_KEY) {
            Bucket hit = byTitle.get(title);
            if (hit != null && (hit.doi.isEmpty() || doi.isEmpty() || hit.doi.equals(doi))) bucket = hit;
        }
        if (bucket == null) {
            bucket = new Bucket();
            bucket.row.key = CandidateRanker.paperKeyOf(source);
            if (bucket.row.key.isEmpty()) bucket.row.key = "#" + buckets.size();
            buckets.add(bucket);
        }
        if (!doi.isEmpty() && bucket.doi.isEmpty()) bucket.doi = doi;
        if (!title.isEmpty() && bucket.title.isEmpty()) bucket.title = title;
        // 两个键都登记：先到的只有题名、后到的带 DOI，第三条只带题名时也找得回同一个桶。
        if (!bucket.doi.isEmpty()) byDoi.put(bucket.doi, bucket);
        if (bucket.title.length() >= CandidateRanker.MIN_TITLE_KEY) byTitle.put(bucket.title, bucket);
        bucket.sources++;
        fillIdentity(bucket.row, source);
        return bucket;
    }

    /** 第一个非空值即赢，和 CandidateRanker.adopt 同一条规则；engine 不在这里定，它按命中逐条累计。 */
    private static void fillIdentity(Row row, TextCorpus.Source source) {
        if (row.doi.isEmpty()) row.doi = CandidateRanker.doiOf(source);
        if (row.title.isEmpty()) row.title = safe(source.title);
        if (row.authors.isEmpty()) row.authors = safe(source.authors);
        if (row.year.isEmpty()) row.year = safe(source.year);
        if (row.locator.isEmpty()) row.locator = safe(source.locator);
    }

    /** 一条命中记进它自己那篇、它自己那个检索源名下。 */
    private static void charge(Bucket bucket, TextCorpus.Source source, TextCorpus.Hit hit, String norm) {
        Row row = bucket.row;
        int valid = TextCorpus.validCount(norm, hit.start, hit.end);
        row.duplicateChars += valid;
        row.hitCount++;
        if (row.firstStart < 0 || hit.start < row.firstStart) row.firstStart = hit.start;
        if (hit.end - hit.start > row.longestEnd - row.longestStart) {
            row.longestStart = hit.start;
            row.longestEnd = hit.end;
        }
        if (hit.score > row.topScore) row.topScore = hit.score;
        String engine = engineKey(source);
        int at = row.engineKeys.indexOf(engine);
        if (at < 0) {
            row.engineKeys.add(engine);
            row.engineChars.add(Integer.valueOf(valid));
        } else {
            row.engineChars.set(at, Integer.valueOf(row.engineChars.get(at).intValue() + valid));
        }
    }

    /** 超出上限的篇目折成最后一行；只丢展示，一行字符都不丢。 */
    private static void fold(SourceLedger ledger) {
        ArrayList<Row> rows = ledger.rows;
        if (rows.size() <= MAX_ROWS) return;
        Row tail = new Row();
        tail.others = true;
        tail.key = "~";
        tail.othersCount = rows.size() - MAX_ROWS;
        for (int i = MAX_ROWS; i < rows.size(); i++) {
            Row row = rows.get(i);
            tail.duplicateChars += row.duplicateChars;
            tail.hitCount += row.hitCount;
            tail.sourceCount += row.sourceCount;
            if (row.firstStart >= 0 && (tail.firstStart < 0 || row.firstStart < tail.firstStart))
                tail.firstStart = row.firstStart;
            for (int e = 0; e < row.engineKeys.size(); e++) {
                String engine = row.engineKeys.get(e);
                int at = tail.engineKeys.indexOf(engine);
                int chars = row.engineChars.get(e).intValue();
                if (at < 0) {
                    tail.engineKeys.add(engine);
                    tail.engineChars.add(Integer.valueOf(chars));
                } else {
                    tail.engineChars.set(at, Integer.valueOf(tail.engineChars.get(at).intValue() + chars));
                }
            }
        }
        while (rows.size() > MAX_ROWS) rows.remove(rows.size() - 1);
        tail.engine = labels(tail.engineKeys);
        rows.add(tail);
    }

    private static int sum(ArrayList<Row> rows) {
        int total = 0;
        for (int i = 0; i < rows.size(); i++) total += rows.get(i).duplicateChars;
        return total;
    }

    /** 检索源键：TextCorpus 把空引擎记成 "local"，两侧必须同一个写法，两张表才对得上。 */
    private static String engineKey(String engine) {
        return engine == null || engine.length() == 0 ? "local" : engine;
    }

    private static String engineKey(TextCorpus.Source source) {
        return engineKey(source == null ? null : source.engine);
    }

    private static String labels(ArrayList<String> engineKeys) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < engineKeys.size(); i++) {
            if (out.length() > 0) out.append(" + ");
            out.append(PaperSources.label(engineKeys.get(i)));
        }
        return out.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * 这次到底比了哪些库、各多少篇：按「检索源 × 可比材料档」逐组数篇数与字数。
 * 纯函数，输入只有比对基线那份语料（自建库与联网候选都在里面），不检索、不碰界面。
 *
 * 为什么必须有这张表：结果页原来只说"总相似度比 X%"，读者没法知道这个 X% 是拿什么比出来的——
 * 自建库那十几篇是他自己导的原文，知网那三十条只有摘要，两批命中根本不是一把尺。
 * 篇数与字数只认 {@code Source.engine} 与 {@code Source.material} 两个字段，不解析任何题录内容。
 * 没记档的那一批单列一组，不并进"正文"：宁可多出一列难看的，也不让没记档的篇数冒充正文库的篇数。
 */
public final class CorpusLedger {
    /** 最多列几组：一个源两档，十几个源就二十几组，手机那一屏装不下，多出来的折成一组"其余"。 */
    public static final int MAX_ROWS = 12;
    /** 材料档空缺时的组名。它故意不与 MATERIAL_FULL 合并。 */
    public static final String MATERIAL_UNGRADED = "未记档";
    /** 自建库在语料里的引擎键（TextCorpus 侧的写法，与 SourceLedger.engineKey 同一条规矩）。 */
    static final String ENGINE_LOCAL = "local";

    /** 一组 = 一个检索源在一个材料档下的入库情况。 */
    public static final class Row {
        public String engine = "";
        public String material = "";
        /** 进库几篇。 */
        public int papers;
        /** 这几篇拿来比对的有效字数之和。 */
        public int chars;

        /** 组名：检索源在前、档位在后，"知网（…） · 只有摘要可比"。 */
        public String label() {
            return sourceLabel(engine) + " · " + materialLabel(material);
        }
    }

    public final ArrayList<Row> rows = new ArrayList<Row>();
    /** 折叠前的全量组数与篇数、字数。 */
    public int groups, papers, chars;
    /**
     * 按档分的篇数：正文、精要、摘要、其余（仅题录进不了语料，只有未记档那一类会落进 otherPapers）。
     * 三者相加 == papers。
     */
    public int fullPapers, digestPapers, abstractPapers, otherPapers;

    private CorpusLedger() { }

    /** 语料为空返回一张空表（rows 空、papers 0），不抛。 */
    public static CorpusLedger aggregate(TextCorpus corpus) {
        CorpusLedger ledger = new CorpusLedger();
        if (corpus == null) return ledger;
        LinkedHashMap<String, Row> byKey = new LinkedHashMap<String, Row>();
        for (int i = 0; i < corpus.sourceCount(); i++) {
            TextCorpus.Source source = corpus.sourceAt(i);
            String engine = source == null || source.engine == null || source.engine.length() == 0
                    ? ENGINE_LOCAL : source.engine;
            String material = source == null || source.material == null || source.material.length() == 0
                    ? MATERIAL_UNGRADED : source.material;
            String key = engine + "\u0000" + material;
            Row row = byKey.get(key);
            if (row == null) {
                row = new Row();
                row.engine = engine;
                row.material = material;
                byKey.put(key, row);
            }
            row.papers++;
            row.chars += Math.max(0, corpus.sourceCharsAt(i));
        }
        for (String key : byKey.keySet()) {
            Row row = byKey.get(key);
            ledger.rows.add(row);
            ledger.papers += row.papers;
            ledger.chars += row.chars;
            if (DuplicateEngine.MATERIAL_FULL.equals(row.material)) ledger.fullPapers += row.papers;
            else if (DuplicateEngine.MATERIAL_DIGEST.equals(row.material)) ledger.digestPapers += row.papers;
            else if (DuplicateEngine.MATERIAL_ABSTRACT.equals(row.material)) ledger.abstractPapers += row.papers;
            else ledger.otherPapers += row.papers;
        }
        ledger.groups = ledger.rows.size();
        fold(ledger);
        return ledger;
    }

    /** 超出上限的组只并字数与篇数：一组的题名都不解析，也就没什么可丢的。 */
    private static void fold(CorpusLedger ledger) {
        if (ledger.rows.size() <= MAX_ROWS) return;
        Row tail = new Row();
        tail.engine = "";
        tail.material = "其余 " + (ledger.rows.size() - MAX_ROWS) + " 组";
        for (int i = MAX_ROWS; i < ledger.rows.size(); i++) {
            tail.papers += ledger.rows.get(i).papers;
            tail.chars += ledger.rows.get(i).chars;
        }
        while (ledger.rows.size() > MAX_ROWS) ledger.rows.remove(ledger.rows.size() - 1);
        ledger.rows.add(tail);
    }

    /**
     * 一行说清比了哪些库、各多少篇。空语料返回空串——界面据此连那一行都不画，
     * 而不是画一句"比了 0 篇"，那与"没得比"在结果页上会长得一样。
     */
    public String summaryLine() {
        if (rows.isEmpty()) return "";
        StringBuilder out = new StringBuilder("本次比对材料 " + papers + " 篇（" + groups + " 组）：");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) out.append(" · ");
            out.append(rows.get(i).label()).append(' ').append(rows.get(i).papers).append(" 篇");
        }
        return out.toString();
    }

    /** 检索源名：自建库在 PaperSources 那张表里没有条目，这里补上，界面上别再露 "local"。 */
    public static String sourceLabel(String engine) {
        if (engine == null || engine.length() == 0) return "未记来源";
        if (ENGINE_LOCAL.equals(engine)) return "自建库";
        return PaperSources.label(engine);
    }

    /**
     * 档位名：与 SourceLedger.materialLabel 同一套话，两处不许各起一名。
     * 每一档的名字自己要说清"比的是什么"，不许让"精要"读成"正文"。
     */
    public static String materialLabel(String material) {
        if (DuplicateEngine.MATERIAL_FULL.equals(material)) return "正文";
        if (DuplicateEngine.MATERIAL_DIGEST.equals(material)) return "只有精要可读";
        if (DuplicateEngine.MATERIAL_ABSTRACT.equals(material)) return "只有摘要可比";
        if (DuplicateEngine.MATERIAL_RECORD.equals(material)) return "只有题录";
        return MATERIAL_UNGRADED;
    }
}
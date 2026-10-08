package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 降重为什么只换掉命中区里三成的字：同一份靶子，逐段量四件事。
 *
 * 一、这段命中区里有多少字是"可动字"（不在不动区掩码里），其中多少字落在替换表的候选词上；
 * 二、表里有候选的那些点，规则自己说落下几个、被哪条规矩挡下几个（LocalRewriter 的实测计数）；
 * 三、闭环真采纳的那份文本里，逐词比出来实际换掉了几个；
 * 四、缺口拆成四笔账：掩码吃掉的、guard 挡掉的、次数上限外的、表里压根没有候选的。
 *
 * 口径与 RewriteRateRegression 逐字相同：同一个库、同一份插句靶子、同一套 Limits、同一条判据
 * （RewriteLoop 内部用的就是那一条 match），所以这里的 516 -> 398 与那条回归跑出来的是同一个数。
 * 词表不在这里复刻第二份：出现点用 LocalRewriter.lexicalTable()，规则点数用 LocalRewriter 自己的
 * pattern 扫出来的实测计数。实词槽位是另一个口径（按标点与一张固定封闭类虚词表切，长度 >= 2 的
 * 连续汉字串），只用来说"有多少个词位"，覆盖率与替换率都按字数算，因为查重器数的就是字数。
 *
 * 只吃本地语料，不进 tools/test-host.ps1 的必跑清单。跑法：
 *   java com.rikkahub.wordlite.RewriteCoverageAudit tests/samples/input-liu.docx \
 *       tests/corpus/oa-planted-cjmenet.txt artifacts/tests/rewrite-coverage
 */
public final class RewriteCoverageAudit {
    private static int checks;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError("改写覆盖实测: " + message);
        checks++;
    }

    private static void head(String title) {
        System.out.println("");
        System.out.println("== " + title + " ==");
    }

    private static void line(String label, String value) {
        System.out.println("  " + pad(label, 30) + value);
    }

    private static String pad(String text, int width) {
        StringBuilder out = new StringBuilder(text);
        while (out.length() < width) out.append(' ');
        return out.toString();
    }

    private static String pct(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    /** 只用来切"实词槽位"：封闭类虚词与标点，固定一张表，不引第三方分词。 */
    private static final String PARTICLES = "的了和与或在对把被而并且则以及于从上等就都也还很更最要是为之中前后上下";

    private static boolean particle(char c) {
        return PARTICLES.indexOf(c) >= 0 || "，。、；：！？（）《》“”‘’「」【】 \t\r\n".indexOf(c) >= 0;
    }

    /** 覆盖区间并集：一个字符被任意一个候选词盖住就算"有候选"。 */
    private static int coveredChars(boolean[] banned, boolean[] covered) {
        int n = 0;
        for (int i = 0; i < banned.length; i++) if (!banned[i] && covered[i]) n++;
        return n;
    }

    private static boolean[] bannedFlags(String text, TextProtection.Mask mask) {
        boolean[] banned = new boolean[text.length()];
        for (int i = 0; i < mask.islands.size(); i++) {
            TextProtection.Island island = mask.islands.get(i);
            for (int at = Math.max(0, island.start); at < Math.min(banned.length, island.end); at++) banned[at] = true;
        }
        return banned;
    }

    private static int movableChars(boolean[] banned, String text) {
        int n = 0;
        for (int i = 0; i < banned.length; i++) if (!banned[i] && !particle(text.charAt(i))) n++;
        return n;
    }

    /**
     * 术语字根：槽位里含这些字根就按"领域术语"计。它只用来把覆盖率的上限说清楚——这些位置按
     * 意义安全规则不许换，所以它们不是"偷懒没加词"，是"不该加词"。字根一律取两个字以上，
     * 免得用一个单字把普通词位也算成术语，把上限说虚高。
     */
    private static final String[] TERM_ROOTS = {
        "切削", "切屑", "刀具", "温度", "应变", "应力", "变形", "冷却", "润滑", "乳化", "钛", "铝合金",
        "裂纹", "形貌", "机理", "规律", "测力", "频率", "参数", "表面", "传导", "微观", "试验", "加工",
        "工件", "剪切", "绝热", "液氮", "摩擦", "介质", "电流", "焊料", "失效",
    };

    private static boolean hasTermRoot(String slot) {
        for (int i = 0; i < TERM_ROOTS.length; i++) if (slot.contains(TERM_ROOTS[i])) return true;
        return false;
    }

    /** 把一批无候选槽位拆成"术语（不该换）"与"开放词位（以后可以加词的地方）"，字数按整槽计，偏大。 */
    private static String termSplit(LinkedHashMap<String, Integer> slots, ArrayList<String> openOut) {
        int termSlots = 0, termChars = 0, openSlots = 0, openChars = 0;
        for (String key : slots.keySet()) {
            int times = slots.get(key).intValue();
            if (hasTermRoot(key)) {
                termSlots++;
                termChars += key.length() * times;
            } else {
                openSlots++;
                openChars += key.length() * times;
                if (openOut != null) openOut.add(key);
            }
        }
        return "含术语字根 " + termSlots + " 个 / " + termChars + " 字，"
                + "不含术语字根 " + openSlots + " 个 / " + openChars + " 字";
    }

    /** 实词槽位：掩码外、按标点与虚词切出的长度 >= 2 的连续汉字串。 */
    private static int slots(boolean[] banned, String text, ArrayList<String> out) {
        StringBuilder slot = new StringBuilder();
        int n = 0;
        for (int i = 0; i <= text.length(); i++) {
            boolean keep = i < text.length() && !banned[i] && !particle(text.charAt(i));
            if (keep) slot.append(text.charAt(i));
            if (i == text.length() || !keep) {
                if (slot.length() >= 2) { n++; if (out != null) out.add(slot.toString()); }
                slot.setLength(0);
            }
        }
        return n;
    }

    private static int occurrences(String needle, String text) {
        if (needle.isEmpty()) return 0;
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) n++;
        return n;
    }

    /** 逐字符 LCS：改掉的字数 = 原句长 - LCS，加进来的字数 = 改后长 - LCS。 */
    private static int lcsLength(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? prev[j - 1] + 1 : Math.max(prev[j], cur[j - 1]);
            }
            int[] swap = prev; prev = cur; cur = swap;
            java.util.Arrays.fill(cur, 0);
        }
        return prev[b.length()];
    }
    /* ============ 两把与自家判据无关的尺：字符二元组 TF-IDF 余弦 + ROUGE-L ============ */

    /**
     * 这两把尺不许引用 TextCorpus 的任何常量、切句、袋或三元组：它们的存在就是为了回答
     * "是不是只学会了躲自家那三个阈值"。输入只有清洗后的字符序列。
     */
    private static String clean(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString();
    }

    private static void addBigrams(String text, HashMap<String, Integer> tf) {
        String s = clean(text);
        for (int i = 1; i < s.length(); i++) {
            String key = s.substring(i - 1, i + 1);
            Integer old = tf.get(key);
            tf.put(key, old == null ? Integer.valueOf(1) : Integer.valueOf(old.intValue() + 1));
        }
    }

    /** 背景语料 = 被抄那篇文档自己的每一句。idf = log((1+N)/(1+df))。 */
    private static HashMap<String, Double> idfOf(ArrayList<String> background) {
        HashMap<String, Integer> df = new HashMap<String, Integer>();
        for (int i = 0; i < background.size(); i++) {
            HashMap<String, Integer> tf = new HashMap<String, Integer>();
            addBigrams(background.get(i), tf);
            for (String key : tf.keySet()) {
                Integer old = df.get(key);
                df.put(key, old == null ? Integer.valueOf(1) : Integer.valueOf(old.intValue() + 1));
            }
        }
        HashMap<String, Double> idf = new HashMap<String, Double>();
        double n = background.size();
        for (String key : df.keySet()) {
            int docFreq = df.get(key).intValue();
            idf.put(key, Double.valueOf(Math.log((1d + n) / (1d + docFreq))));
        }
        return idf;
    }

    private static HashMap<String, Double> vectorOf(String text, HashMap<String, Double> idf) {
        HashMap<String, Integer> tf = new HashMap<String, Integer>();
        addBigrams(text, tf);
        HashMap<String, Double> vector = new HashMap<String, Double>();
        for (String key : tf.keySet()) {
            Double weight = idf.get(key);
            if (weight == null) continue;
            double value = tf.get(key).intValue() * weight.doubleValue();
            if (value != 0d) vector.put(key, Double.valueOf(value));
        }
        return vector;
    }

    private static double cosine(HashMap<String, Double> a, HashMap<String, Double> b) {
        double dot = 0d, na = 0d, nb = 0d;
        for (String key : a.keySet()) {
            double value = a.get(key).doubleValue();
            na += value * value;
            Double other = b.get(key);
            if (other != null) dot += value * other.doubleValue();
        }
        for (String key : b.keySet()) nb += b.get(key).doubleValue() * b.get(key).doubleValue();
        return na <= 0d || nb <= 0d ? 0d : dot / Math.sqrt(na * nb);
    }

    /** ROUGE-L：按字的 LCS 的 F1。 */
    private static double rougeL(String hypothesis, String reference) {
        String h = clean(hypothesis), r = clean(reference);
        if (h.isEmpty() || r.isEmpty()) return 0d;
        int lcs = lcsLength(h, r);
        double precision = (double) lcs / (double) h.length();
        double recall = (double) lcs / (double) r.length();
        return precision + recall <= 0d ? 0d : 2d * precision * recall / (precision + recall);
    }

    /** 一段话对被抄来源的三个读数：整篇余弦、最像那一句的余弦、最像那一句的 ROUGE-L。 */
    private static final class Yardstick {
        final double cosDoc, cosMax, rougeMax;
        Yardstick(double cosDoc, double cosMax, double rougeMax) {
            this.cosDoc = cosDoc; this.cosMax = cosMax; this.rougeMax = rougeMax;
        }
    }

    private static Yardstick measure(String paragraph, String sourceDoc, ArrayList<String> sourceSentences,
                                     HashMap<String, Double> idf) {
        HashMap<String, Double> query = vectorOf(paragraph, idf);
        double cosDoc = cosine(query, vectorOf(sourceDoc, idf));
        double cosMax = 0d, rouge = 0d;
        for (int i = 0; i < sourceSentences.size(); i++) {
            cosMax = Math.max(cosMax, cosine(query, vectorOf(sourceSentences.get(i), idf)));
            rouge = Math.max(rouge, rougeL(paragraph, sourceSentences.get(i)));
        }
        return new Yardstick(cosDoc, cosMax, rouge);
    }

    private static ArrayList<String> sentenceList(String text) {
        ArrayList<String> out = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("。！？；\n\r".indexOf(c) >= 0) {
                if (current.length() > 0) out.add(current.toString());
                current.setLength(0);
            } else current.append(c);
        }
        if (current.length() > 0) out.add(current.toString());
        return out;
    }

    /** 调序：句子还是那几句、先后变了。 */
    private static boolean reordered(String before, String after) {
        ArrayList<String> a = sentenceList(before), b = sentenceList(after);
        if (a.size() < 2 || a.size() != b.size() || a.equals(b)) return false;
        ArrayList<String> sa = new ArrayList<String>(a), sb = new ArrayList<String>(b);
        java.util.Collections.sort(sa);
        java.util.Collections.sort(sb);
        return sa.equals(sb);
    }

    /** 一个命中段的全部账。 */
    private static final class Case {
        final ArrayList<String> bareSlots = new ArrayList<String>();
        final ArrayList<String> bareAfterSlots = new ArrayList<String>();
        int index, dupBefore, dupAfter, movable, covered, slots, slotsCovered;
        int seen, masked, legal, guard, capped, landed, bannedByTable, insideCandidateNotAdopted;
        int otherLegal, otherGuard, otherCapped;    // 结构化/词序规则（不在替换表里的那批）
        int removed, added;
        boolean reordered, touched;
        Yardstick before, after;
    }

    /** 一个表内左端词在整批命中区里的账。 */
    private static final class Row {
        final String strategy, from, to;
        int seen, masked, legal, islandMismatch, rejectList, clauseGuard, pastCap, single, landed;
        Row(String strategy, String from, String to) { this.strategy = strategy; this.from = from; this.to = to; }
        int guard() { return islandMismatch + rejectList + clauseGuard; }
        int capped() { return pastCap + single; }
        /** 表里有这个词，但规则的正则带着前后禁字，禁字挡住了——只能按残差算，别当精确数。 */
        int bannedByTable() { return Math.max(0, seen - legal - guard()); }
    }

    private static Row rowFor(LinkedHashMap<String, Row> rows, String[] tableRow) {
        String key = tableRow[0] + ":" + tableRow[1];
        Row row = rows.get(key);
        if (row == null) { row = new Row(tableRow[0], tableRow[1], tableRow[2]); rows.put(key, row); }
        return row;
    }

    private static Row rowByKey(LinkedHashMap<String, Row> rows, String key) { return rows.get(key); }
    public static void main(String[] args) throws Exception {
        String source = args.length > 0 ? args[0] : "tests/samples/input-liu.docx";
        String sentenceFile = args.length > 1 ? args[1] : "tests/corpus/oa-planted-cjmenet.txt";
        String outDir = args.length > 2 ? args[2] : "artifacts/tests/rewrite-coverage";
        new java.io.File(outDir).mkdirs();
        List<String> terms = RewriteRateRegression.TERMS;
        List<String> sentenceRows = RewriteRateRegression.lines(sentenceFile);
        check(sentenceRows.size() == 11, "固定语料是 11 句（实得 " + sentenceRows.size() + "）");
        check(LocalRewriter.REASONS.length == 6,
                "实测计数的列序变了（" + java.util.Arrays.toString(LocalRewriter.REASONS) + "），这里的列要跟着改");

        TextCorpus corpus = new TextCorpus();
        TextCorpus.Source src = new TextCorpus.Source();
        src.id = "local:oa-planted-cjmenet";
        src.title = "自建库：钛铝合金低温切削（开放获取，11 句派生）";
        StringBuilder joined = new StringBuilder();
        for (String s : sentenceRows) joined.append(s).append("。\n");
        String sourceDoc = joined.toString();
        corpus.add(src, sourceDoc);

        String targetFile = outDir + "/input-liu-planted.docx";
        RewriteRateRegression.plant(source, sentenceRows, targetFile);
        String before = RewriteRateRegression.text(targetFile);
        TextCorpus.Report b = RewriteRateRegression.judge(corpus, before);

        RewriteLoop.Limits limits = new RewriteLoop.Limits();
        limits.rounds = 10;
        limits.depth = 8;
        limits.candidates = 8;
        limits.verifications = 240;
        long began = System.currentTimeMillis();
        RewriteLoop.Result r = RewriteLoop.run(before, corpus, terms, limits);

        head("闭环读数（与 RewriteRateRegression 同一把尺）");
        line("改写前", pct(b.overallRate) + "  命中 " + b.duplicateChars + "/" + b.comparedChars
                + " 字，" + b.hits.size() + " 处");
        line("改写后", pct(r.rateAfter) + "  命中 " + r.dupAfter + "/" + r.comparedChars
                + " 字，" + r.hitsAfter + " 处");
        line("预算与用量", "depth " + limits.depth + "、candidates " + limits.candidates
                + "、rounds " + limits.rounds + "、verifications " + limits.verifications
                + "；实used " + r.verified + " 次，文本健全性断言挡下 " + r.rejected + " 个候选");
        check(b.duplicateChars == 516, "改写前真值 516 字（实测 " + b.duplicateChars + "）");
        check(r.dupAfter < r.dupBefore, "闭环确实让整篇命中字数变小（没变小就该回退）");

        HashMap<String, Double> idf = idfOf(new ArrayList<String>(sentenceRows));
        List<String[]> table = LocalRewriter.lexicalTable();
        LinkedHashMap<String, Row> rows = new LinkedHashMap<String, Row>();
        LinkedHashMap<String, int[]> structural = new LinkedHashMap<String, int[]>();
        ArrayList<Case> cases = new ArrayList<Case>();
        int index = 0;
        for (RewriteLoop.Segment seg : RewriteLoop.details(r)) {
            if (seg.dupBefore <= 0) continue;
            Case item = new Case();
            item.index = ++index;
            item.dupBefore = seg.dupBefore;
            item.dupAfter = seg.dupAfter;
            String piece = seg.original;
            String adopted = seg.adopted == null || seg.adopted.isEmpty() ? piece : seg.adopted;
            DocxDocument.ParagraphBlock block = new DocxDocument.ParagraphBlock();
            block.text = piece;
            TextProtection.Mask mask = TextProtection.mask(block, 0, piece.length(), terms);
            boolean[] banned = bannedFlags(piece, mask);
            item.movable = movableChars(banned, piece);
            boolean[] covered = new boolean[piece.length()];
            for (String[] tableRow : table) {
                String from = tableRow[1];
                for (int at = piece.indexOf(from); at >= 0; at = piece.indexOf(from, at + from.length())) {
                    Row row = rowFor(rows, tableRow);
                    boolean hidden = false;
                    for (int k = at; k < at + from.length(); k++) if (banned[k]) { hidden = true; break; }
                    if (hidden) { row.masked++; item.masked++; continue; }
                    row.seen++;
                    item.seen++;
                    for (int k = at; k < at + from.length(); k++) covered[k] = true;
                }
            }
            item.covered = coveredChars(banned, covered);
            ArrayList<String> slotList = new ArrayList<String>();
            item.slots = slots(banned, piece, slotList);
            for (int i = 0; i < slotList.size(); i++) {
                boolean hasCandidate = false;
                for (int t = 0; t < table.size(); t++) {
                    if (slotList.get(i).contains(table.get(t)[1])) { hasCandidate = true; break; }
                }
                if (hasCandidate) item.slotsCovered++;
                else item.bareSlots.add(slotList.get(i));
            }
            // 改完还在命中的段：把采纳文本按同一个口径再切一遍。这张表才是"该往表里加哪个词"的唯一依据。
            if (item.dupAfter > 0) {
                DocxDocument.ParagraphBlock afterBlock = new DocxDocument.ParagraphBlock();
                afterBlock.text = adopted;
                boolean[] afterBanned = bannedFlags(adopted,
                        TextProtection.mask(afterBlock, 0, adopted.length(), terms));
                ArrayList<String> afterSlots = new ArrayList<String>();
                slots(afterBanned, adopted, afterSlots);
                for (int i = 0; i < afterSlots.size(); i++) {
                    boolean hasCandidate = false;
                    for (int t = 0; t < table.size(); t++) {
                        if (afterSlots.get(i).contains(table.get(t)[1])) { hasCandidate = true; break; }
                    }
                    if (!hasCandidate) item.bareAfterSlots.add(afterSlots.get(i));
                }
            }
            LocalRewriter.startTally();
            LocalRewriter.rewrite(mask.submitted, terms, limits.depth, limits.candidates);
            LocalRewriter.Tally tally = LocalRewriter.stopTally();
            check(tally != null, "实测计数没装上，后面的账都是空的");
            for (String key : tally.rows.keySet()) {
                int[] cells = tally.rows.get(key);
                Row row = rowByKey(rows, key);
                if (row == null) {                 // 结构化/词序规则：不进逐词表，另记一笔
                    int[] other = structural.get(key);
                    if (other == null) { other = new int[3]; structural.put(key, other); }
                    other[0] += cells[0];
                    other[1] += cells[1] + cells[2] + cells[3];
                    other[2] += cells[4] + cells[5];
                    item.otherLegal += cells[0];
                    item.otherGuard += cells[1] + cells[2] + cells[3];
                    item.otherCapped += cells[4] + cells[5];
                    continue;
                }
                row.legal += cells[0];
                row.islandMismatch += cells[1];
                row.rejectList += cells[2];
                row.clauseGuard += cells[3];
                row.pastCap += cells[4];
                row.single += cells[5];
                item.legal += cells[0];
                item.guard += cells[1] + cells[2] + cells[3];
                item.capped += cells[4] + cells[5];
            }
            for (Row row : rows.values()) {
                int gone = occurrences(row.from, piece) - occurrences(row.from, adopted);
                int gained = occurrences(row.to, adopted) - occurrences(row.to, piece);
                if (gone > 0 && gained > 0) {
                    int landed = Math.min(gone, gained);
                    row.landed += landed;
                    item.landed += landed;
                }
            }
            int lcs = lcsLength(piece, adopted);
            item.removed = piece.length() - lcs;
            item.added = adopted.length() - lcs;
            item.reordered = reordered(piece, adopted);
            item.touched = item.removed > 0 || item.added > 0;
            item.bannedByTable = Math.max(0, item.seen - item.legal - item.guard);
            item.insideCandidateNotAdopted = Math.max(0, item.legal - item.landed - item.capped);
            item.before = measure(piece, sourceDoc, new ArrayList<String>(sentenceRows), idf);
            item.after = measure(adopted, sourceDoc, new ArrayList<String>(sentenceRows), idf);
            cases.add(item);
            check(item.movable > 0, "第 " + item.index + " 段量得出可动字");
        }

        head("表 1：命中区逐段——多少字能动、动得了的里面多少有候选");
        System.out.println("  " + pad("段", 5) + pad("命中字前->后", 15) + pad("可动字", 8)
                + pad("有候选字", 10) + pad("覆盖率", 9) + pad("槽位", 7) + pad("有候选槽", 10)
                + pad("点:出现", 8) + pad("可落", 6) + pad("落地", 6) + pad("改掉字", 8) + pad("替换率", 9));
        int movable = 0, covered = 0, slotTotal = 0, slotCovered = 0, seen = 0, legal = 0, landed = 0;
        int maskedPoints = 0, guardPoints = 0, capPoints = 0, banned = 0, notAdopted = 0;
        int otherLegal = 0, otherGuard = 0, otherCapped = 0;
        int removedTotal = 0, addedTotal = 0;
        double cosDocBefore = 0d, cosDocAfter = 0d, cosMaxBefore = 0d, cosMaxAfter = 0d;
        double rougeBefore = 0d, rougeAfter = 0d;
        int hitBefore = 0, hitAfter = 0, swapCases = 0, cutCases = 0, orderCases = 0, touchedCases = 0;
        for (Case item : cases) {
            System.out.println("  " + pad(Integer.toString(item.index), 5)
                    + pad(item.dupBefore + " -> " + item.dupAfter, 15)
                    + pad(Integer.toString(item.movable), 8)
                    + pad(Integer.toString(item.covered), 10)
                    + pad(pct(100d * item.covered / Math.max(1, item.movable)), 9)
                    + pad(Integer.toString(item.slots), 7)
                    + pad(Integer.toString(item.slotsCovered), 10)
                    + pad(Integer.toString(item.seen), 8)
                    + pad(Integer.toString(item.legal), 6)
                    + pad(Integer.toString(item.landed), 6)
                    + pad(Integer.toString(item.removed), 8)
                    + pad(pct(100d * item.removed / Math.max(1, item.movable)), 9));
            movable += item.movable;
            covered += item.covered;
            slotTotal += item.slots;
            slotCovered += item.slotsCovered;
            seen += item.seen;
            legal += item.legal;
            landed += item.landed;
            maskedPoints += item.masked;
            guardPoints += item.guard;
            capPoints += item.capped;
            banned += item.bannedByTable;
            otherLegal += item.otherLegal;
            otherGuard += item.otherGuard;
            otherCapped += item.otherCapped;
            notAdopted += item.insideCandidateNotAdopted;
            removedTotal += item.removed;
            addedTotal += item.added;
            if (item.landed > 0) swapCases++;
            if (item.removed > item.landed) cutCases++;
            if (item.reordered) orderCases++;
            if (item.touched) touchedCases++;
            hitBefore += item.dupBefore;
            hitAfter += item.dupAfter;
            cosDocBefore += item.before.cosDoc;
            cosDocAfter += item.after.cosDoc;
            cosMaxBefore += item.before.cosMax;
            cosMaxAfter += item.after.cosMax;
            rougeBefore += item.before.rougeMax;
            rougeAfter += item.after.rougeMax;
        }
        int n = cases.size();
        check(n > 0, "命中区里量得出段");

        head("表 2：缺口拆账——可动字到改掉字之间少了谁");
        line("可动字（掩码外、非虚词）", Integer.toString(movable));
        line("其中有候选词盖住的字数", covered + "  覆盖率 " + pct(100d * covered / Math.max(1, movable)));
        line("表里根本没有候选的字数", (movable - covered) + "  " + pct(100d * (movable - covered) / Math.max(1, movable)));
        line("槽位口径", slotTotal + " 个槽位，其中 " + slotCovered + " 个含候选词（"
                + pct(100d * slotCovered / Math.max(1, slotTotal)) + "）");
        line("表内出现点（掩码外）", Integer.toString(seen));
        line("被不动区掩码吃掉的出现点", Integer.toString(maskedPoints));
        line("规则可落的点", legal + "  占出现点 " + pct(100d * legal / Math.max(1, seen)));
        line("表内禁字（前后禁字）挡掉", banned + "（按残差算：出现点 - 可落 - guard）");
        line("guard 挡掉（掩码不符/反例名单/小句开头）", Integer.toString(guardPoints));
        line("次数与单点规矩挡掉", capPoints + "（depth " + limits.depth + "、candidates " + limits.candidates + "）");
        line("落进采纳文本的点", landed + "  占出现点 " + pct(100d * landed / Math.max(1, seen)));
        line("在候选里但没进采纳文本", notAdopted + "（闭环只认让整篇命中字数变小的候选）");
        line("同口径再看替换表外的规则", "出现点 " + (otherLegal + otherGuard + otherCapped)
                + "，可落 " + otherLegal + "，guard 挡 " + otherGuard + "，上限外 " + otherCapped
                + "（结构化/词序规则，不数进上面的表内账）");

        head("表 2b：表里根本没有候选的槽位（这些词位就是覆盖率的缺口，逐字打出来给扩表用）");
        LinkedHashMap<String, Integer> bare = new LinkedHashMap<String, Integer>();
        for (Case item : cases) {
            for (String slot : item.bareSlots) {
                Integer old = bare.get(slot);
                bare.put(slot, old == null ? Integer.valueOf(1) : Integer.valueOf(old.intValue() + 1));
            }
        }
        ArrayList<String> bareNames = new ArrayList<String>(bare.keySet());
        java.util.Collections.sort(bareNames, new java.util.Comparator<String>() {
            public int compare(String a, String b) {
                int byCount = bare.get(b).intValue() - bare.get(a).intValue();
                if (byCount != 0) return byCount;
                int byLength = b.length() - a.length();
                return byLength != 0 ? byLength : a.compareTo(b);
            }
        });
        StringBuilder band = new StringBuilder("  ");
        int shown = 0;
        for (String slot : bareNames) {
            if (shown++ >= 60) break;
            band.append(slot).append('(').append(bare.get(slot).intValue()).append(")  ");
            if (band.length() > 92) {
                System.out.println(band.toString());
                band.setLength(0);
                band.append("  ");
            }
        }
        if (band.length() > 2) System.out.println(band.toString());
        line("这一批缺口按术语拆一刀", termSplit(bare, null));

        head("表 2c：改完仍在命中的段里，替换表仍然盖不到的槽位（加词只以这张表为依据）");
        LinkedHashMap<String, Integer> still = new LinkedHashMap<String, Integer>();
        int stillSegments = 0;
        for (Case item : cases) {
            if (item.dupAfter <= 0) continue;
            stillSegments++;
            for (String slot : item.bareAfterSlots) {
                Integer old = still.get(slot);
                still.put(slot, old == null ? Integer.valueOf(1) : Integer.valueOf(old.intValue() + 1));
            }
        }
        ArrayList<String> stillNames = new ArrayList<String>(still.keySet());
        java.util.Collections.sort(stillNames, new java.util.Comparator<String>() {
            public int compare(String a, String b) {
                int byCount = still.get(b).intValue() - still.get(a).intValue();
                if (byCount != 0) return byCount;
                int byLength = b.length() - a.length();
                return byLength != 0 ? byLength : a.compareTo(b);
            }
        });
        line("仍被命中的段 / 无候选槽位", stillSegments + " 段，" + stillNames.size() + " 个去重槽位");
        StringBuilder again = new StringBuilder("  ");
        int printed = 0;
        for (String slot : stillNames) {
            if (printed++ >= 60) break;
            again.append(slot).append('(').append(still.get(slot).intValue()).append(")  ");
            if (again.length() > 92) {
                System.out.println(again.toString());
                again.setLength(0);
                again.append("  ");
            }
        }
        if (again.length() > 2) System.out.println(again.toString());
        ArrayList<String> openResidue = new ArrayList<String>();
        line("这一批缺口按术语拆一刀", termSplit(still, openResidue));
        StringBuilder tail = new StringBuilder("  开放词位（以后能加词的完整清单，逐字）：");
        for (String slot : openResidue) {
            if (slot.length() < 2) continue;
            if (tail.length() + slot.length() + 3 > 96) {
                System.out.println(tail.toString());
                tail.setLength(0);
            }
            tail.append(slot).append('、');
        }
        if (tail.length() > 0) System.out.println(tail.toString());

        head("表 2d：替换表外那批规则（结构化/词序）的实测点");
        System.out.println("  " + pad("规则", 30) + pad("出现", 6) + pad("可落", 6)
                + pad("guard", 7) + pad("上限外", 8));
        for (String key : structural.keySet()) {
            int[] cells = structural.get(key);
            if (cells[0] + cells[1] + cells[2] == 0) continue;
            System.out.println("  " + pad(key, 30)
                    + pad(Integer.toString(cells[0] + cells[1] + cells[2]), 6)
                    + pad(Integer.toString(cells[0]), 6)
                    + pad(Integer.toString(cells[1]), 7)
                    + pad(Integer.toString(cells[2]), 8));
        }

        head("表 3：三把尺，同一批命中段（均值）");
        System.out.println("  " + pad("段", 5) + pad("判据命中字前->后", 18) + pad("余弦(整篇来源)", 17)
                + pad("余弦(最像那句)", 17) + pad("ROUGE-L(最像那句)", 19));
        for (Case item : cases) {
            System.out.println("  " + pad(Integer.toString(item.index), 5)
                    + pad(item.dupBefore + " -> " + item.dupAfter, 18)
                    + pad(num(item.before.cosDoc) + " -> " + num(item.after.cosDoc), 17)
                    + pad(num(item.before.cosMax) + " -> " + num(item.after.cosMax), 17)
                    + pad(num(item.before.rougeMax) + " -> " + num(item.after.rougeMax), 19));
        }
        line("均值（" + n + " 段）", "判据命中字 " + hitBefore + " -> " + hitAfter
                + "（降 " + pct(100d * (1d - (double) hitAfter / Math.max(1, hitBefore))) + "）");
        line("余弦(整篇来源) 均值", num(cosDocBefore / n) + " -> " + num(cosDocAfter / n)
                + "（降 " + pct(100d * (1d - cosDocAfter / Math.max(1e-9d, cosDocBefore))) + "）");
        line("余弦(最像那句) 均值", num(cosMaxBefore / n) + " -> " + num(cosMaxAfter / n)
                + "（降 " + pct(100d * (1d - cosMaxAfter / Math.max(1e-9d, cosMaxBefore))) + "）");
        line("ROUGE-L 均值", num(rougeBefore / n) + " -> " + num(rougeAfter / n)
                + "（降 " + pct(100d * (1d - rougeAfter / Math.max(1e-9d, rougeBefore))) + "）");

        head("表 4：闭环动的是哪几种操作（" + n + " 段里各占几段）");
        line("换字（有表内替换落地）", swapCases + " / " + n);
        line("删字（改掉的字数明显多于替换点数）", cutCases + " / " + n);
        line("调序（句子还是那几句、先后变了）", orderCases + " / " + n);
        line("被改到的段 / 字数", touchedCases + " / " + n + " 段，改掉的字数 " + removedTotal
                + "，加进来的字数 " + addedTotal);

        head("逐词表：出现过的表内词（按出现点排序）");
        ArrayList<Row> ordered = new ArrayList<Row>(rows.values());
        java.util.Collections.sort(ordered, new java.util.Comparator<Row>() {
            public int compare(Row a, Row b) {
                if (a.seen != b.seen) return b.seen - a.seen;
                return a.strategy.compareTo(b.strategy) <= 0 ? -1 : 1;
            }
        });
        System.out.println("  " + pad("策略:词 -> 替换", 30) + pad("出现", 6) + pad("可落", 6)
                + pad("禁字", 6) + pad("guard", 7) + pad("上限外", 8) + pad("掩码里", 8) + pad("落地", 6));
        for (Row row : ordered) {
            if (row.seen == 0 && row.landed == 0) continue;
            System.out.println("  " + pad(row.strategy + ":" + row.from + " -> " + row.to, 30)
                    + pad(Integer.toString(row.seen), 6)
                    + pad(Integer.toString(row.legal), 6)
                    + pad(Integer.toString(row.bannedByTable()), 6)
                    + pad(Integer.toString(row.guard()), 7)
                    + pad(Integer.toString(row.capped()), 8)
                    + pad(Integer.toString(row.masked), 8)
                    + pad(Integer.toString(row.landed), 6));
        }

        head("结论");
        double detectorDrop = 1d - (double) hitAfter / Math.max(1, hitBefore);
        double rougeDrop = 1d - rougeAfter / Math.max(1e-9d, rougeBefore);
        double cosDrop = 1d - cosMaxAfter / Math.max(1e-9d, cosMaxBefore);
        System.out.println("  判据命中字数降 " + pct(100d * detectorDrop) + "，同一批段的 ROUGE-L 降 "
                + pct(100d * rougeDrop) + "，余弦(最像那句)降 " + pct(100d * cosDrop));
        System.out.println("  覆盖率（有候选字/可动字）" + pct(100d * covered / Math.max(1, movable))
                + "，替换率（落地/出现点）" + pct(100d * landed / Math.max(1, seen))
                + "，改掉的字数占可动字 " + pct(100d * removedTotal / Math.max(1, movable)));
        if (detectorDrop - rougeDrop > 0.10d) {
            System.out.println("  判定：判据降得比 ROUGE-L 多 " + pct(100d * (detectorDrop - rougeDrop))
                    + " 以上——这一版里有相当一部分是躲开自家判据，不是把话改到别处也认不出。");
        } else {
            System.out.println("  判定：判据降的幅度与 ROUGE-L/余弦同步，没有只学会躲自己。");
        }
        System.out.println("  耗时 " + (System.currentTimeMillis() - began) + " ms，"
                + checks + " 条断言通过（闭环整篇验证 " + r.verified + " 次）");
        check(covered > 0, "至少量出一片有候选的字");
        check(landed > 0, "闭环确实让表内替换落进了采纳文本");
    }

    private static String num(double value) {
        return String.format(Locale.ROOT, "%.4f", Double.valueOf(value));
    }
}

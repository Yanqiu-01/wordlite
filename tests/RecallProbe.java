package com.rikkahub.wordlite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 端到端召回实测台：把一句真论文的原文原封不动地种进一篇稿子里，然后问三件事——
 * ① 内置检索有没有把那句话出自的那篇论文捞回来；② 比对内核有没有把那句标成重复；
 * ③ 标出来的出处是不是真的那一篇。
 *
 * <p>这台机器<strong>不在闸门里</strong>：它要联网，一个种子要向每个源各问一次。它存在的理由是这样一条
 * 区别：{@code CnkiLiveProbe} 量的是"返回的题录和查询词像不像"（相关度，一个代理指标），
 * 这里量的是"抄了真句子到底抓不抓得住"（召回，产品真正要对用户负责的那个数）。
 * 相关度 11.5% 这种数字可以吓唬人，只有召回率能说明查重是真是假。
 *
 * <p>种子句不是编的：先从每个源按主题问一次，拿回来的真摘要里挑一句（有效字符 &gt;= 22），
 * 再把这句原样种进由真人语料拼成的稿子里。种子来自被测系统自己返回的文献，所以"抓不住"不能拿
 * "库里没有这篇"当借口——它确实在库里，而且是刚从同一个检索口拿出来的。
 *
 * <p>每个种子向<strong>所有</strong>勾选的源各问一次，语料按生产链路那样合在一份里比对：用户用的
 * 是"一次查重问九个源"，逐源单独召回只对他的一半有意义。所以这里同时报两个数——
 * 出自哪个源的那一句被哪个源问回来了（自召回），以及有没有任何一个源问回来（并集召回，
 * 这才是产品口径）。命中名次一并打出来，因为"排在第 6 条"在页面尺寸 5 的设置下等于没回来。
 *
 * 跑法见 {@code tools/recall-probe.ps1}：
 *   java com.rikkahub.wordlite.RecallProbe [proxy] [perEngine] [engines] [query] [filler]
 */
public final class RecallProbe {
    /** 种一句之前要让路，与生产链路每源两次提问间隔 400ms 同一个理由。 */
    private static final long REQUEST_GAP_MILLIS = 400L;
    private static final int MIN_SEED_VALID_CHARS = 22;
    private static final int MIN_CORPUS_ABSTRACT_CHARS = 60;

    private RecallProbe() { }

    public static void main(String[] argv) throws Exception {
        String proxy = argv.length > 0 ? argv[0].trim() : "";
        int per = argv.length > 1 ? Integer.parseInt(argv[1]) : 12;
        String engineList = argv.length > 2 ? argv[2] : "cnki,wanfang,cqvip";
        String query = argv.length > 3 ? argv[3]
                : "碳化硅 瞬态液相扩散焊 界面组织 中间层 接头性能";
        String filler = filler(argv.length > 4 ? argv[4] : "tests/corpus/real-prose.txt");
        int seedsPerEngine = argv.length > 5 ? Integer.parseInt(argv[5]) : 4;

        String[] engines = engineList.split(",");
        PaperSources.Limits limits = new PaperSources.Limits();
        limits.perEngine = per;
        limits.timeoutSeconds = 25;
        limits.proxy = proxy;
        System.out.println("probe per_engine_page=" + per + " engines=" + engineList
                + " proxy=" + (proxy.isEmpty() ? "直连优先" : proxy) + " seeds_per_engine=" + seedsPerEngine);

        Map<String, int[]> perEngine = new LinkedHashMap<String, int[]>();
        int plants = 0, anyRetrieved = 0, flagged = 0, attributed = 0;
        for (String raw : engines) {
            String home = raw.trim();
            if (home.isEmpty()) continue;
            List<PaperSources.Candidate> seeds = ask(home, query, limits);
            System.out.println("== " + home + " seeds=" + (seeds == null ? "QUERY FAILED" : String.valueOf(seeds.size())));
            if (seeds == null) continue;
            int used = 0;
            for (PaperSources.Candidate seed : seeds) {
                if (used >= seedsPerEngine) break;
                String sentence = seedSentence(seed);
                if (sentence == null) continue;
                used++;
                plants++;
                Document doc = Document.plant(filler, sentence);

                Map<String, Integer> ranks = new LinkedHashMap<String, Integer>();
                Map<String, List<PaperSources.Candidate>> rounds =
                        new LinkedHashMap<String, List<PaperSources.Candidate>>();
                for (String rawEngine : engines) {
                    String engine = rawEngine.trim();
                    if (engine.isEmpty()) continue;
                    List<PaperSources.Candidate> found = ask(engine, sentence, limits);
                    rounds.put(engine, found);
                    ranks.put(engine, rankOf(found, seed));
                }

                TextCorpus corpus = new TextCorpus();
                for (List<PaperSources.Candidate> found : rounds.values()) index(corpus, found);
                TextCorpus.Report report = corpus.match(doc.text, null,
                        TextCorpus.structure(doc.text).spanArray());
                TextCorpus.Hit over = covering(report, doc.start, doc.end);
                boolean isFlagged = over != null;
                boolean isAttributed = isFlagged && samePaper(over.source, seed.source);
                /* 同一篇论文常在几个库里各有一条记录，维普那条连题名都没有。判"出处对不对"时先把
                   文献号对上；对不上再看被点名的那条摘要里有没有原封不动的这句话——有，就是同一篇
                   在另一个库里的抄本，指给它不算错（实测有 6 例是指给知网那条带真题名的记录）。 */
                boolean isTwin = isFlagged && (!isAttributed) && containsSentence(rounds, over.source, sentence);
                boolean anyFound = false;
                StringBuilder where = new StringBuilder();
                for (String engine : ranks.keySet()) {
                    int rank = ranks.get(engine).intValue();
                    if (rank >= 0) anyFound = true;
                    where.append(engine).append('=').append(rank < 0 ? "-" : String.valueOf(rank + 1)).append(' ');
                }

                int[] row = perEngine.get(home);
                if (row == null) perEngine.put(home, row = new int[6]);
                row[0]++;
                row[1] += anyFound ? 1 : 0;
                row[2] += ranks.get(home).intValue() >= 0 ? 1 : 0;
                row[3] += isFlagged ? 1 : 0;
                row[4] += (isAttributed || isTwin) ? 1 : 0;
                row[5] += isAttributed ? 1 : 0;
                anyRetrieved += anyFound ? 1 : 0;
                flagged += isFlagged ? 1 : 0;
                attributed += (isAttributed || isTwin) ? 1 : 0;

                System.out.println(String.format(Locale.US, "  any=%-5s self=%-5s flagged=%-5s attributed=%-5s %s",
                        anyFound, ranks.get(home).intValue() >= 0, isFlagged, isAttributed, where));
                System.out.println(String.format(Locale.US, "       seed=%s", cut(seed.source.title, 32)));
                System.out.println(String.format(Locale.US, "       sentence[%s]", cut(sentence, 44)));
                if (isFlagged && !isAttributed)
                    System.out.println(String.format(Locale.US, "       blamed twin=%s id=[%s] title=[%s]  wanted id=[%s] title=[%s]",
                            isTwin, cut(over.source.id, 30), cut(over.source.title, 26),
                            cut(seed.source.id, 30), cut(seed.source.title, 26)));
            }
        }
        System.out.println("PER-ENGINE SUMMARY (plants / any / self / flagged / attributed(含同源抄本) / 同一文献号)");
        for (String home : perEngine.keySet()) {
            int[] row = perEngine.get(home);
            System.out.println(String.format(Locale.US, "  %-8s %d / %d / %d / %d / %d / %d   self=%.0f%% any=%.0f%%",
                    home, row[0], row[1], row[2], row[3], row[4], row[5], pct(row[2], row[0]), pct(row[1], row[0])));
        }
        System.out.println(String.format(Locale.US,
                "SUMMARY plants=%d union_retrieval_recall=%.1f%% detection_recall=%.1f%% attribution_recall=%.1f%%",
                plants, pct(anyRetrieved, plants), pct(flagged, plants), pct(attributed, plants)));
        if (plants == 0) {
            System.out.println("NOTE 一个种子句都没种成——摘要太短或检索全空，这个 SUMMARY 不代表检不出，只代表探针没跑起来。");
            System.exit(2);
        }
    }

    private static List<PaperSources.Candidate> ask(String engine, String query, PaperSources.Limits limits) {
        try {
            List<PaperSources.Candidate> out = PaperSources.search(engine, query, limits, null);
            sleep();
            return out;
        } catch (Exception error) {
            System.out.println("       " + engine + " query failed: " + error.getMessage());
            sleep();
            return null;
        }
    }

    /** 种子句取摘要中段那句：开头是"针对……"这类套话的位置，也是查重系统最容易蒙对的位置。 */
    private static String seedSentence(PaperSources.Candidate seed) {
        String abstractText = seed == null ? null : seed.abstractText;
        if (abstractText == null || abstractText.length() < 60) return null;
        ArrayList<int[]> ranges = TextCorpus.sentences(abstractText);
        int best = -1;
        for (int i = 0; i < ranges.size(); i++) {
            int[] range = ranges.get(i);
            if (TextCorpus.validCount(abstractText, range[0], range[1]) < MIN_SEED_VALID_CHARS) continue;
            if (best < 0) best = i;
            if (i >= ranges.size() / 2) break;
        }
        if (best < 0) return null;
        int[] range = ranges.get(best);
        String sentence = abstractText.substring(range[0], range[1]).trim();
        return sentence.indexOf('\n') >= 0 ? null : sentence;
    }

    private static void index(TextCorpus corpus, List<PaperSources.Candidate> candidates) {
        if (candidates == null) return;
        for (PaperSources.Candidate candidate : candidates) {
            String text = candidate.abstractText == null ? "" : candidate.abstractText;
            if (text.length() < MIN_CORPUS_ABSTRACT_CHARS) continue;
            TextCorpus.Source source = new TextCorpus.Source();
            source.id = candidate.source.id;
            source.title = candidate.source.title;
            source.authors = candidate.source.authors;
            source.year = candidate.source.year;
            source.locator = candidate.source.locator;
            source.engine = candidate.source.engine;
            corpus.add(source, text);
        }
    }

    /** 被点名的那条候选，摘要里是不是原样有这句话——有就是同一篇在别的库里的记录。 */
    private static boolean containsSentence(Map<String, List<PaperSources.Candidate>> rounds,
                                            TextCorpus.Source blamed, String sentence) {
        if (blamed == null) return false;
        String needle = TextCorpus.normalize(sentence);
        for (List<PaperSources.Candidate> found : rounds.values()) {
            if (found == null) continue;
            for (PaperSources.Candidate candidate : found) {
                /* 语料里的 Source 是探针新建的对象，与候选的不是同一个引用，只能按文献号认；
                   这里刻意不走题名兜底——维普的题名是刊名，同刊同期两条会被判成同一条。 */
                boolean sameRecord = (candidate.source.id != null && blamed.id != null
                        && !candidate.source.id.isEmpty() && candidate.source.id.equals(blamed.id))
                        || (candidate.source.locator != null && blamed.locator != null
                        && !candidate.source.locator.isEmpty() && candidate.source.locator.equals(blamed.locator));
                if (!sameRecord) continue;
                String hay = TextCorpus.normalize(candidate.abstractText);
                if (!needle.isEmpty() && hay.contains(needle)) return true;
            }
        }
        return false;
    }

    /** 是不是同一篇。题名归一化只是一条兜底：维普的服务端渲染里题名整个缺席（实测 <a class="title"> 是空的），
     * 来源只能署到"《刊名》 年 期"，用题名判归属等于在同一刊同期的两条记录之间掷硬币。
     * 文献号才是硬键，所以先看 id，再退到 locator，最后才认题名。
     */
    private static boolean samePaper(TextCorpus.Source hit, TextCorpus.Source seed) {
        if (hit == null || seed == null) return false;
        if (!hit.id.isEmpty() && hit.id.equals(seed.id)) return true;
        if (!hit.locator.isEmpty() && hit.locator.equals(seed.locator)) return true;
        String wanted = CandidateRanker.titleKey(seed.title);
        return !wanted.isEmpty() && wanted.equals(CandidateRanker.titleKey(hit.title));
    }

    /** 那篇论文在本次返回的第几条（0 起），认文献号；不在列表里给 -1。 */
    private static int rankOf(List<PaperSources.Candidate> found, PaperSources.Candidate seed) {
        if (found == null || seed == null) return -1;
        for (int i = 0; i < found.size(); i++)
            if (samePaper(found.get(i).source, seed.source)) return i;
        return -1;
    }

    /** 只要有一段命中与种下的句子重叠就算检出——重复区间常把上下句并进来，要求完全对齐等于换个理由判失败。 */
    private static TextCorpus.Hit covering(TextCorpus.Report report, int start, int end) {
        if (report == null) return null;
        TextCorpus.Hit best = null;
        for (TextCorpus.Hit hit : report.hits) {
            if (hit.end <= start || hit.start >= end) continue;
            if (best == null || hit.end - hit.start > best.end - best.start) best = hit;
        }
        return best;
    }

    /** 种句用的稿子：真人语料里连续的一段，够长到不会被当成"整篇抄袭"，也不会自己撞上候选摘要。 */
    private static String filler(String path) throws Exception {
        String all = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (String line : all.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.length() < 40) continue;
            out.append(trimmed).append('\n');
            if (out.length() > 1200) break;
        }
        if (out.length() < 300) throw new IllegalStateException("filler corpus too short: " + path);
        return out.toString();
    }

    private static final class Document {
        final String text;
        final int start, end;
        Document(String text, int start, int end) { this.text = text; this.start = start; this.end = end; }

        static Document plant(String filler, String sentence) {
            String head = filler.substring(0, Math.min(filler.length(), 600));
            String tail = filler.substring(Math.min(filler.length(), 600));
            int start = head.length() + 1;
            return new Document(head + "\n" + sentence + "\n" + tail, start, start + sentence.length());
        }
    }

    private static double pct(int part, int whole) {
        return whole == 0 ? 0d : 100d * part / whole;
    }

    private static void sleep() {
        try { Thread.sleep(REQUEST_GAP_MILLIS); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static String cut(String value, int max) {
        if (value == null || value.isEmpty()) return "-";
        String flat = value.replace('\n', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }
}

package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * 检索候选的打分、跨源去重与配额挑选，三个纯函数，不联网、不碰 Android，host 回归可直接跑。
 *
 * 为什么要单独拆这一层：DuplicateEngine.search() 现在是"谁先回来谁先占额度"——第一个引擎返回的
 * 候选一律入库，MAX_FULL_TEXTS 次开放获取全花在它们身上，后面引擎里真正对题的那篇只能排到队尾；
 * 而跨源重复只在"同一引擎内"按 locator 去掉（DuplicateEngine.known 要求 engine 也相同），
 * 同一篇论文从 Crossref 和 OpenAlex 各回来一次就占两个入库名额、可能抓两次全文。
 * 这里补的是排序和合并，取数逻辑一行不动。
 */
public final class CandidateRanker {
    /**
     * Okapi BM25 的两个参数取 Lucene 默认 k1=1.2 / b=0.75，出处见 docs/oss-algorithms.md
     * 「候选检索排序」一节（BM25Similarity.java）。同一节还记着"摘要之间长度差异远小于网页，
     * b=0.75 会过度惩罚长摘要"。这里不动 b 的取值，只在 lengthFactor() 里把"短文档白送的加分"削掉；
     * 长摘要挨罚的那一侧原样保留，所以 docs 里那条顾虑在这里只解决了一半，另一半是一个常量：
     * 真机语料上确认长摘要被压得太狠，就把 B 调低，别在别处绕过去。
     */
    static final double K1 = 1.2, B = 0.75;
    /**
     * 文档长度下限（字符三元组个数，约五十字）。够不到这个量多半是解析残骸：维普解析失败时
     * 手里就只有"维普记录 93742010"一个标题。依据是 docs/oss-algorithms.md 落到本项目的硬规则
     * "按比例 + 按字数双门槛"，以及 WCopyfind 用绝对量（共享词总数 >=100）而不是比例当闸门。
     */
    static final int MIN_DOC_GRAMS = 48;
    /** 短于该长度的标题指纹太通用（"图像分割"四个字谁都有），不许凭它合并两篇。 */
    static final int MIN_TITLE_KEY = 6;
    /**
     * 只在"键"里剥掉的中文标题尾缀。中文论文标题的通行句式是"基于X的Y研究"，同一个东西
     * 在知网写作"……的研究"、在万方写作"……"，键里不剥就并不上；显示用的标题一个字都不动。
     * 长的排前面，先剥长尾缀，免得"的研究与设计"被拆成两次短剥离后剩下半截。
     */
    private static final String[] TITLE_TAILS = {
            "的设计与实现", "的设计与开发", "的研究与设计", "的现状与思考", "的问题与对策", "的应用研究",
            "应用研究", "研究综述", "研究进展", "文献综述", "综述", "述评", "浅议", "浅析", "浅谈", "试论",
            "初探", "探索", "探究", "思考", "启示", "对策", "策略", "方法", "进展", "概述", "分析", "研究",
            "应用", "的",
    };

    private CandidateRanker() { }

    /** 一条候选的打分结果。 */
    public static final class Scored {
        public final PaperSources.Candidate candidate;
        public final double score;
        /** 命中的查询三元组个数：比例之外还要看绝对量，这是本项目"双门槛"里的第二条。 */
        public final int matchedTerms;
        /** 该候选标题+摘要的三元组总数，即 BM25 的 dl，给报告和回归看。 */
        public final int documentLength;

        Scored(PaperSources.Candidate candidate, double score, int matchedTerms, int documentLength) {
            this.candidate = candidate;
            this.score = score;
            this.matchedTerms = matchedTerms;
            this.documentLength = documentLength;
        }
    }

    /** 检索循环该执行的一条：名次决定入库顺序，fetchFullText 决定这一名值不值得花一次全文抓取。 */
    public static final class Selection {
        public final PaperSources.Candidate candidate;
        public final double score;
        public final int rank;
        public final boolean fetchFullText;

        Selection(PaperSources.Candidate candidate, double score, int rank, boolean fetchFullText) {
            this.candidate = candidate;
            this.score = score;
            this.rank = rank;
            this.fetchFullText = fetchFullText;
        }
    }

    /** 一次合并的账目：谁留下、谁被并掉、按哪个键合的、凭什么留它。调用方要能在报告里复述这一行。 */
    public static final class Merged {
        public final PaperSources.Candidate kept, dropped;
        public final String keyKind, key, reason;

        Merged(PaperSources.Candidate kept, PaperSources.Candidate dropped,
               String keyKind, String key, String reason) {
            this.kept = kept;
            this.dropped = dropped;
            this.keyKind = keyKind;
            this.key = key;
            this.reason = reason;
        }
    }

    /** 去重结果：留下的候选（保持池内原序）与全部合并账目。 */
    public static final class Dedup {
        public final ArrayList<PaperSources.Candidate> kept = new ArrayList<PaperSources.Candidate>();
        public final ArrayList<Merged> merges = new ArrayList<Merged>();
    }

    /**
     * BM25 排序：查询串对每条候选的"标题+摘要"打分，df 就在这一池候选里算。
     * 标题与摘要当一个字段，和 OpenAlex 的 title_and_abstract.search 同口径
     * （docs/oss-algorithms.md「候选检索排序」记的就是这个检索口）。
     * 池里所有候选都会出现在结果里，包括零分的：检索循环还要靠它们凑满入库数。
     * 查询为空、池为空或池里没有可用对象时返回空表，不抛。
     */
    public static ArrayList<Scored> rank(String query, ArrayList<PaperSources.Candidate> pool) {
        ArrayList<Scored> out = new ArrayList<Scored>();
        if (pool == null || pool.isEmpty()) return out;
        long[] terms = termsFor(query);
        if (terms.length == 0) return out;
        ArrayList<Doc> docs = new ArrayList<Doc>();
        int[] df = new int[terms.length];
        double lengthSum = 0;
        for (int i = 0; i < pool.size(); i++) {
            PaperSources.Candidate candidate = pool.get(i);
            if (candidate == null || candidate.source == null) continue;
            Doc doc = documentOf(candidate, terms, docs.size());
            for (int t = 0; t < terms.length; t++) if (doc.termFrequency[t] > 0) df[t]++;
            lengthSum += doc.length;
            docs.add(doc);
        }
        if (docs.isEmpty()) return out;
        double averageLength = lengthSum / docs.size();
        double[] idf = idfFor(df, docs.size());
        for (int i = 0; i < docs.size(); i++) {
            Doc doc = docs.get(i);
            doc.score = scoreOf(doc, idf, averageLength);
        }
        Collections.sort(docs, DETERMINISTIC);
        for (int i = 0; i < docs.size(); i++) {
            Doc doc = docs.get(i);
            out.add(new Scored(doc.candidate, doc.score, doc.matched, doc.length));
        }
        return out;
    }

    /**
     * 配额挑选：先去重再排序，然后按名次给出检索循环该走的路径。
     * budget 是本次检测允许的开放获取全文抓取次数（对应 DuplicateEngine.MAX_FULL_TEXTS 那种预算），
     * perSourceCap 是单个检索源最多贡献几条（<=0 表示不限）。
     * 两点讲究：一是只有真挂了 fullTextUrl 的候选才吃 budget，没全文链接的高分名不白占额度；
     * 二是 perSourceCap 卡在入库名额上，某一个引擎返回得再多也挤不掉别人的位置。
     * 顺序确定性同上，两次运行逐条一致。
     */
    public static ArrayList<Selection> plan(String query, ArrayList<PaperSources.Candidate> pool,
                                           int budget, int perSourceCap) {
        ArrayList<Selection> out = new ArrayList<Selection>();
        ArrayList<Scored> ranked = rank(query, unique(pool));
        LinkedHashMap<String, Integer> taken = new LinkedHashMap<String, Integer>();
        int fetches = 0, rank = 0;
        for (int i = 0; i < ranked.size(); i++) {
            Scored scored = ranked.get(i);
            String source = sourceKey(scored.candidate);
            int used = count(taken, source);
            if (perSourceCap > 0 && used >= perSourceCap) continue;
            taken.put(source, Integer.valueOf(used + 1));
            rank++;
            boolean fetch = budget > 0 && fetches < budget && hasFullText(scored.candidate);
            if (fetch) fetches++;
            out.add(new Selection(scored.candidate, scored.score, rank, fetch));
        }
        return out;
    }
    // ---- 打分内部件 ----

    /** 一条候选的打分材料：字段、字段长度、每个查询三元组的句级出现次数。 */
    private static final class Doc {
        final PaperSources.Candidate candidate;
        final int length;
        final int[] termFrequency;
        final int position;
        double score;
        int matched;

        Doc(PaperSources.Candidate candidate, int length, int[] termFrequency, int position) {
            this.candidate = candidate;
            this.length = length;
            this.termFrequency = termFrequency;
            this.position = position;
        }
    }

    /**
     * 确定性排序：分数降序 -> source id 升序 -> 标题升序 -> 引擎 id 升序 -> 池内位置。
     * 回归要比对"两次跑出来的顺序逐条一样"，所以平局必须有唯一答案，不能落在 HashMap 的
     * 遍历顺序上，也不能只靠 Arrays.sort 的稳定性（池内顺序本身会被上游引擎顺序带着漂）。
     */
    private static final Comparator<Doc> DETERMINISTIC = new Comparator<Doc>() {
        public int compare(Doc left, Doc right) {
            int byScore = Double.compare(right.score, left.score);
            if (byScore != 0) return byScore;
            int byId = safe(left.candidate.source.id).compareTo(safe(right.candidate.source.id));
            if (byId != 0) return byId;
            int byTitle = safe(left.candidate.source.title).compareTo(safe(right.candidate.source.title));
            if (byTitle != 0) return byTitle;
            int byEngine = sourceKey(left.candidate).compareTo(sourceKey(right.candidate));
            if (byEngine != 0) return byEngine;
            return left.position - right.position;
        }
    };

    /**
     * 术语就是项目自己的字符三元组：TextCorpus.compactOf 归一化去空白，TextCorpus.gramsOf 出哈希，
     * 不另起炉灶分词。整机的倒排、Dice、指纹全建在这套三元组上，换分词等于另造一套语料，
     * 排序口径和匹配口径就会各说各话。
     */
    private static long[] termsFor(String query) {
        if (query == null) return new long[0];
        return TextCorpus.gramsOf(TextCorpus.compactOf(query));
    }

    /** 标题 + 摘要当一个字段，顺序固定，标题在前；换行只用来给切句一个天然边界。 */
    private static String fieldOf(PaperSources.Candidate candidate) {
        String title = safe(candidate.source.title);
        String summary = safe(candidate.abstractText);
        if (title.isEmpty()) return summary;
        if (summary.isEmpty()) return title;
        return title + "\n" + summary;
    }

    /**
     * 一句一条 gram 集合，字段的 dl 取这些集合的并集大小。
     * tf 取句级出现次数而不是"有/无"：gramsOf 会把字段内重复的三元组去掉，直接拿来当 tf 只剩 0/1，
     * k1 的饱和曲线形同虚设；按句计数既留下"整篇反复在谈这个词"的信号，又不像 tf/字数那种
     * 密度算法，摘要一长就把命中摊薄掉。
     * 分段取并集而不是整字段一次 gramsOf，是为了不承认"标题末字 + 摘要首字"拼出来的跨缝三元组：
     * 那是拼接产生的假证据，真检索里没有哪个查询会靠它命中。
     */
    private static Doc documentOf(PaperSources.Candidate candidate, long[] terms, int position) {
        String field = fieldOf(candidate);
        ArrayList<int[]> spans = TextCorpus.sentences(field);
        ArrayList<long[]> pieces = new ArrayList<long[]>();
        int total = 0;
        for (int i = 0; i < spans.size(); i++) {
            int[] span = spans.get(i);
            long[] piece = TextCorpus.gramsOf(TextCorpus.compactOf(field.substring(span[0], span[1])));
            if (piece.length == 0) continue;
            pieces.add(piece);
            total += piece.length;
        }
        int[] frequency = new int[terms.length];
        for (int i = 0; i < pieces.size(); i++) {
            long[] piece = pieces.get(i);
            for (int t = 0; t < terms.length; t++) if (Arrays.binarySearch(piece, terms[t]) >= 0) frequency[t]++;
        }
        return new Doc(candidate, unionSize(pieces, total), frequency, position);
    }

    /** 各句都已排序去重，串起来再排一次数就是并集；返回并集大小，即 dl。 */
    private static int unionSize(ArrayList<long[]> pieces, int total) {
        if (pieces.isEmpty()) return 0;
        long[] merged = new long[total];
        int at = 0;
        for (int i = 0; i < pieces.size(); i++) {
            long[] piece = pieces.get(i);
            System.arraycopy(piece, 0, merged, at, piece.length);
            at += piece.length;
        }
        Arrays.sort(merged);
        int kept = 0;
        for (int i = 0; i < merged.length; i++) if (kept == 0 || merged[kept - 1] != merged[i]) merged[kept++] = merged[i];
        return kept;
    }

    /**
     * BM25 长度项，两处偏离教科书：
     * 一、dl 先抬到 MIN_DOC_GRAMS，二十个字的空壳买不到"短所以占便宜"的长度优势；
     * 二、只罚长、不奖短（结果下限 1）。docs/oss-algorithms.md 已经记下 b=0.75 对摘要这种
     * 窄长度分布不合适，本项目的硬规则又是"按比例 + 按字数双门槛"，而短文档加分恰恰是
     * 纯比例的白送；WCopyfind 的闸门也是绝对量（共享词总数 >=100）而不是比例。
     * 惩罚侧保持 Lucene 原式，dl = 2 * avgdl 时仍是 1 + 0.75，没有偷偷放松。
     */
    static double lengthFactor(int documentLength, double averageLength) {
        if (averageLength <= 0) return 1d;
        double length = documentLength < MIN_DOC_GRAMS ? MIN_DOC_GRAMS : documentLength;
        double norm = 1 - B + B * (length / averageLength);
        return norm < 1 ? 1 : norm;
    }

    /**
     * 单条 idf：log(1 + (N-df+0.5)/(df+0.5))，Lucene BM25Similarity 的写法，出处见 docs/oss-algorithms.md
     * 「候选检索排序」。没有采用 rank_bm25 的 log((N-df+0.5)/(df+0.5)) 原式加 epsilon 底线那一套，
     * 因为候选池常常就两三条（一个检索窗口、一个引擎返回的量）：N=2 而 df=1 时原式正好是 log(1)=0，
     * 满池稀有词的 idf 全等于零，万能句又被 epsilon 抹平，整池分数一起塌成零，排序只能退回输入顺序。
     * Lucene 的写法恒正且随 df 单调下降，小池子不塌，对万能句的压制照样成立：N=6 时 df=1 的词权重
     * 是 df=6 的二十倍。这比 TextCorpus 的 STOP_POSTINGS 一刀切温和，但方向一致——排序要的是让
     * 套话靠后，而不是把它连同它仅有的区分作用一起扔掉。
     */
    static double rawIdf(int df, int total) {
        return Math.log1p((total - df + 0.5d) / (df + 0.5d));
    }

    /** 把每个查询三元组的池内 df 映射成权重；df 已经在池上数完，这里只做映射。 */
    static double[] idfFor(int[] df, int total) {
        double[] idf = new double[df.length];
        for (int i = 0; i < df.length; i++) idf[i] = rawIdf(df[i], total);
        return idf;
    }

    /** 该候选的 BM25 分数，同时把绝对命中量记在 doc.matched 上。 */
    private static double scoreOf(Doc doc, double[] idf, double averageLength) {
        double norm = lengthFactor(doc.length, averageLength);
        double total = 0;
        int matched = 0;
        for (int t = 0; t < idf.length; t++) {
            int tf = doc.termFrequency[t];
            if (tf <= 0) continue;
            matched++;
            total += idf[t] * (tf * (K1 + 1)) / (tf + K1 * norm);
        }
        doc.matched = matched;
        return total;
    }
    // ---- 跨源去重 ----

    /** 一个"同一篇论文"的桶：留下的那条、它的两个键、以及它在 kept 里的位置。 */
    private static final class Bucket {
        PaperSources.Candidate kept;
        String doi = "", title = "";
        int slot;
    }

    /** 去重并只返回留下的候选。 */
    public static ArrayList<PaperSources.Candidate> unique(ArrayList<PaperSources.Candidate> pool) {
        return dedup(pool).kept;
    }

    /**
     * 跨源合并：同一篇论文从 Crossref 和 OpenAlex 各回来一次，只该占一个入库名额、一次全文抓取。
     * 键的取法是 DOI 优先、标题指纹兜底。命中桶之后先定胜者再合并字段，并把败者独有的信息
     * 补给胜者（只补空字段）。池为空或全是空对象时返回空结果，不抛。
     */
    public static Dedup dedup(ArrayList<PaperSources.Candidate> pool) {
        Dedup result = new Dedup();
        if (pool == null) return result;
        LinkedHashMap<String, Bucket> byDoi = new LinkedHashMap<String, Bucket>();
        LinkedHashMap<String, Bucket> byTitle = new LinkedHashMap<String, Bucket>();
        for (int i = 0; i < pool.size(); i++) {
            PaperSources.Candidate candidate = pool.get(i);
            if (candidate == null || candidate.source == null) continue;
            String doi = doiOf(candidate);
            String title = titleKey(candidate.source.title);
            Bucket bucket = doi.isEmpty() ? null : byDoi.get(doi);
            String kind = "doi", key = doi;
            if (bucket == null && title.length() >= MIN_TITLE_KEY) {
                Bucket hit = byTitle.get(title);
                /* 两边都有 DOI 时 DOI 必须相同才算同一篇。同题不同 DOI 是常态——"基于深度学习的
                   图像分割研究"这种标题一个库里能排出几十篇——只按标题合并会误伤。 */
                if (hit != null && (hit.doi.isEmpty() || doi.isEmpty() || hit.doi.equals(doi))
                        && !distinctRecords(hit.kept, candidate)) {
                    bucket = hit;
                    kind = "title";
                    key = title;
                }
            }
            if (bucket == null) {
                bucket = new Bucket();
                bucket.kept = candidate;
                bucket.doi = doi;
                bucket.title = title;
                bucket.slot = result.kept.size();
                result.kept.add(candidate);
                register(bucket, byDoi, byTitle);
                continue;
            }
            PaperSources.Candidate incumbent = bucket.kept;
            PaperSources.Candidate winner = better(candidate, incumbent) ? candidate : incumbent;
            PaperSources.Candidate loser = winner == candidate ? incumbent : candidate;
            adopt(winner, loser);
            bucket.kept = winner;
            result.kept.set(bucket.slot, winner);
            String winnerDoi = doiOf(winner);
            if (!winnerDoi.isEmpty()) bucket.doi = winnerDoi;
            String winnerTitle = titleKey(winner.source.title);
            if (!winnerTitle.isEmpty()) bucket.title = winnerTitle;
            register(bucket, byDoi, byTitle);
            result.merges.add(new Merged(winner, loser, kind, key, account(kind, key, winner, loser)));
        }
        return result;
    }

    /**
     * 同一个检索源给出的两个不同文献号就是两篇，题名一样也不并桶。
     *
     * <p>这条是给维普兜底的：它的检索结果是服务端渲染的，题名整个缺席（实测题名那个
     * {@code <a class="title">} 是空标签，真题名要登录后由脚本再填），所以候选只能署成
     * "《矿冶工程》 2022年第2期"这种"刊名+年+期"。同一期里的两篇共用这一个串，按题名并桶会把
     * 两篇并成一篇——白丢一次可比对的摘要，来源榜还把重复记到错的文献号上（实测：种一句维普真原文，
     * 维普每次都在第一批里给回一篇同刊同期但不同号的文章，见 docs/retrieval-recall.md）。
     *
     * <p>只管同源。跨源不在这条的射程里：知网与万方给同一篇论文两个号是常态，号不同说明不了任何事，
     * 那一侧仍然只能靠 DOI 相同或题名指纹相同。两边任一没有号时也照旧并桶，别把 0.6.0 的合并能力削掉。
     */
    static boolean distinctRecords(PaperSources.Candidate left, PaperSources.Candidate right) {
        if (left == null || right == null || left.source == null || right.source == null) return false;
        String leftId = left.source.id == null ? "" : left.source.id.trim();
        String rightId = right.source.id == null ? "" : right.source.id.trim();
        if (leftId.isEmpty() || rightId.isEmpty()) return false;
        String leftEngine = left.source.engine == null ? "" : left.source.engine.trim();
        String rightEngine = right.source.engine == null ? "" : right.source.engine.trim();
        if (!leftEngine.equals(rightEngine)) return false;
        return !leftId.equals(rightId);
    }

    /** 两个键都登记：后来者不管带着 DOI 还是只带着标题，都能找回同一个桶。 */
    private static void register(Bucket bucket, LinkedHashMap<String, Bucket> byDoi,
                                 LinkedHashMap<String, Bucket> byTitle) {
        if (!bucket.doi.isEmpty()) byDoi.put(bucket.doi, bucket);
        if (bucket.title.length() >= MIN_TITLE_KEY) byTitle.put(bucket.title, bucket);
    }

    /**
     * 把败者独有的信息并到胜者身上，只补空字段、绝不覆盖已有值，也不动显示用的标题。
     * 为什么必须补：跨源去重最容易踩的坑就是留下没有全文链接的那条、把挂着开放获取 PDF 的那条
     * 扔掉，白丢一次可比对的全文。就地改，池里的对象本来就归调用方所有。
     */
    private static void adopt(PaperSources.Candidate winner, PaperSources.Candidate loser) {
        if (safe(winner.abstractText).trim().isEmpty()) winner.abstractText = safe(loser.abstractText);
        if (safe(winner.fullTextUrl).trim().isEmpty()) winner.fullTextUrl = safe(loser.fullTextUrl);
        TextCorpus.Source to = winner.source, from = loser.source;
        if (safe(to.locator).isEmpty()) to.locator = safe(from.locator);
        if (safe(to.id).isEmpty()) to.id = safe(from.id);
        if (safe(to.authors).isEmpty()) to.authors = safe(from.authors);
        if (safe(to.year).isEmpty()) to.year = safe(from.year);
        if (safe(to.engine).isEmpty()) to.engine = safe(from.engine);
    }

    /** 谁留下：先比有没有摘要，再比摘要有效字数，再比标题长度，全平保留池内靠前的一条。 */
    private static boolean better(PaperSources.Candidate left, PaperSources.Candidate right) {
        int leftChars = abstractChars(left), rightChars = abstractChars(right);
        if ((leftChars > 0) != (rightChars > 0)) return leftChars > 0;
        if (leftChars != rightChars) return leftChars > rightChars;
        return safe(left.source.title).length() > safe(right.source.title).length();
    }

    /** 一整行账目：按哪个键合的、谁留下、谁被并、凭什么，报告里可以直接照抄。 */
    private static String account(String kind, String key, PaperSources.Candidate winner,
                                 PaperSources.Candidate loser) {
        String via = "doi".equals(kind) ? "DOI " + key : "标题指纹 " + key;
        return "按" + via + " 合一条：" + describe(winner) + " 留下，" + describe(loser)
                + " 并入；" + why(winner, loser);
    }

    /** 合并账目里"哪一条"的写法：引擎 + 标题前二十四字，够认出来就行，报告里不塞长文。 */
    private static String describe(PaperSources.Candidate candidate) {
        String engine = safe(candidate.source.engine);
        String title = safe(candidate.source.title);
        return (engine.isEmpty() ? "未记来源" : engine) + "《"
                + (title.length() <= 24 ? title : title.substring(0, 24)) + "》";
    }

    /** 留它的理由，和 better() 的判据一一对应，报告里要能照着这句话复述。 */
    private static String why(PaperSources.Candidate winner, PaperSources.Candidate loser) {
        int left = abstractChars(winner), right = abstractChars(loser);
        if (left != right) return right == 0
                ? "保留有摘要的一条（" + left + " 字），被并的那条只有题录"
                : "两条都有摘要，保留更长的：" + left + " 字 > " + right + " 字";
        int leftTitle = safe(winner.source.title).length(), rightTitle = safe(loser.source.title).length();
        if (leftTitle != rightTitle)
            return "两条摘要同为 " + left + " 字，保留标题更完整的那条：" + leftTitle + " > " + rightTitle + " 字符";
        return "两条信息量相同（摘要 " + left + " 字），保留池内靠前的那条";
    }

    // ---- 键与小工具 ----

    /** DOI 允许的字符。normalize 已把大写折成小写、全角折成半角，所以这里只有小写。 */
    private static boolean isDoiChar(char c) {
        if (c >= 'a' && c <= 'z') return true;
        if (c >= '0' && c <= '9') return true;
        return c == '.' || c == '_' || c == '-' || c == '/' || c == ';' || c == '(' || c == ')';
    }

    /** 尾随的句读和括号是句子带进来的，不是 DOI 自己的。 */
    private static String trimKeyTail(String value) {
        int end = value.length();
        while (end > 0) {
            char c = value.charAt(end - 1);
            if (c == '.' || c == ',' || c == ';' || c == ':' || c == '/' || c == '?' || c == '!'
                    || c == ')' || c == ']' || c == '"' || c == '\'') { end--; continue; }
            break;
        }
        return value.substring(0, end);
    }

    /**
     * 从 locator / id 里取规范 DOI，只认 10.注册机构/后缀 这一个语法。
     * 不能"看着像标识符就当键"：ncpssd 的 locator 是页面 URL 或 "ncpssd:12345"、europepmc 是
     * "PMID:123"、维普是 "cqvip.com/doc/xxx"，各自成族，整串当键既合并不了同一篇，又可能把两个
     * 不同系统里的同号记录错并成一条。要求串里必须有一个 "/" 就是这道闸。
     */
    static String doiKey(String value) {
        if (value == null) return "";
        String text = TextCorpus.normalize(value);
        for (int at = text.indexOf("10."); at >= 0; at = text.indexOf("10.", at + 3)) {
            int end = at;
            while (end < text.length() && isDoiChar(text.charAt(end))) end++;
            String run = trimKeyTail(text.substring(at, end));
            int slash = run.indexOf('/');
            if (slash > 0 && slash < run.length() - 1) return run;
        }
        return "";
    }

    /**
     * 标题指纹：normalize 负责全半角、大小写、繁简和标点折叠（它是逐字符等长的现成工具，
     * 不必自己重写一遍），再去掉所有非字非数（标点、空格、连接符一律不算），最后剥掉通用尾缀。
     * 剥尾缀只作用于键，显示用的标题一个字都不动；不足 MIN_TITLE_KEY 的键交给调用方判空。
     */
    static String titleKey(String title) {
        String compact = TextCorpus.compactOf(title);
        StringBuilder out = new StringBuilder(compact.length());
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < compact.length() && Character.isLowSurrogate(compact.charAt(i + 1))) {
                    out.append(c).append(compact.charAt(i + 1));
                    i++;
                }
                continue;
            }
            if (Character.isLowSurrogate(c)) continue;
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return stripTails(out.toString());
    }

    /** 反复剥尾缀，但一旦剩下的字数够不到 MIN_TITLE_KEY 就收手，免得两篇都只剩四个字被错并。 */
    private static String stripTails(String key) {
        String value = key;
        boolean changed = true;
        while (changed && value.length() > MIN_TITLE_KEY) {
            changed = false;
            for (int i = 0; i < TITLE_TAILS.length; i++) {
                String tail = TITLE_TAILS[i];
                if (value.length() - tail.length() >= MIN_TITLE_KEY && value.endsWith(tail)) {
                    value = value.substring(0, value.length() - tail.length());
                    changed = true;
                    break;
                }
            }
        }
        return value;
    }

    /** 一条候选的 DOI：locator 优先（openalex/crossref/europepmc 都写在这儿），再看 id。 */
    private static String doiOf(PaperSources.Candidate candidate) {
        return doiOf(candidate.source);
    }

    /**
     * 一条来源的 DOI 键。来源榜（SourceLedger）把跨检索源的同一条题录并成一行时问这里要，
     * 全项目只此一处 DOI 取法，不许再长第二套。
     */
    static String doiOf(TextCorpus.Source source) {
        if (source == null) return "";
        String key = doiKey(source.locator);
        return key.isEmpty() ? doiKey(source.id) : key;
    }

    /**
     * 展示与排序用的文献键：有 DOI 就是 DOI，没有才退到题名指纹（带 "t:" 前缀，免得和 DOI 撞车），
     * 两个都拿不到返回空串，兜底交给调用方。分区本身仍由 dedup / SourceLedger 的桶逻辑说了算。
     */
    static String paperKeyOf(TextCorpus.Source source) {
        if (source == null) return "";
        String doi = doiOf(source);
        if (!doi.isEmpty()) return doi;
        String title = titleKey(source.title);
        return title.isEmpty() ? "" : "t:" + title;
    }

    /** 配额按检索源计，口径与 DuplicateEngine.candidateCount 用的 engine 字段一致。 */
    static String sourceKey(PaperSources.Candidate candidate) {
        if (candidate == null || candidate.source == null) return "";
        return safe(candidate.source.engine).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasFullText(PaperSources.Candidate candidate) {
        return candidate != null && !safe(candidate.fullTextUrl).trim().isEmpty();
    }

    /** 摘要有效字数，口径用 TextCorpus.validCount：按码点计，去空白与不可见字符。 */
    private static int abstractChars(PaperSources.Candidate candidate) {
        String text = safe(candidate.abstractText);
        if (text.isEmpty()) return 0;
        return TextCorpus.validCount(TextCorpus.normalize(text), 0, text.length());
    }

    private static int count(LinkedHashMap<String, Integer> map, String key) {
        Integer value = map.get(key);
        return value == null ? 0 : value.intValue();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
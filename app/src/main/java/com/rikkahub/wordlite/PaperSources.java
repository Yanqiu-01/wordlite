package com.rikkahub.wordlite;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Built-in literature connectors: query building and response parsing for six public sources. */
public final class PaperSources {
    public static final class Candidate {
        public TextCorpus.Source source = new TextCorpus.Source();
        public String abstractText = "", fullTextUrl = "";
        /** 这条候选最终给本机比对贡献了什么：全文 / 摘要 / 仅题录，由 DuplicateEngine 入库时写，报告逐行可核。 */
        public String comparableMaterial = "";
    }
    public static final class Limits {
        public int timeoutSeconds = 20;
        /** 每次请求向该源要几条：只下发成 per-page / rows / limit / pageSize，不再兼作整轮入库配额。 */
        public int perEngine = 12;
        /** 每篇文档切几个检索窗口 = 每个源最多被问几次，由 EngineSettings.windows 一路传下来。 */
        public int windows = 12;
        /** 本次检测允许的开放获取全文抓取次数。 */
        public int fullTexts = 6;
        /**
         * 本轮还允许"顺手抓正文"几篇：名次队花完全文额度之后，还只有摘要可比、又挂着可直下 PDF 的
         * 候选由它接住。用的是检索剩下的请求额度，与检索抢同一个数。默认 0 = 关，由 app 从检索设置
         * 里写进来——不给默认值，免得哪条没设它的调用路径悄悄开始下载。
         */
        public int autoFullTexts;
        public String coreKey = "";
        /** host:port of an HTTP proxy for this retrieval pass, empty to dial out directly. */
        public String proxy = "";
        /**
         * 每一次检索请求的响应留档出口（A4）。null = 不留档；写了它，这一轮每一次请求都要落一行
         * entries / declaredTotal / 响应形状，**成功也要落**。以前只在失败时留档，于是
         * "HTTP 200 但零条目"与"这一式真的没有货"在代码里长成同一个形状，谁也分不出来。
         * 回调在发请求的那条泳道线程上原地发生，实现方自己负责同步。
         */
        public ShapeSink shapes;

        /**
         * 复制一份本轮要用的额度与出口。检索这一轮不想往调用方递进来的 Limits 上挂本轮的留档出口
         * ——那等于让一次扫描之后仍然有个旧报告被这份设置牵着写。
         * <strong>新增字段必须在这里跟着抄一行</strong>：漏一行就是那一轮拿默认值当用户设置用，
         * AbstractLayerRegression 拿反射逐字段核对这一条。
         */
        public Limits copy() {
            Limits out = new Limits();
            out.timeoutSeconds = timeoutSeconds;
            out.perEngine = perEngine;
            out.windows = windows;
            out.fullTexts = fullTexts;
            out.autoFullTexts = autoFullTexts;
            out.coreKey = coreKey;
            out.proxy = proxy;
            out.shapes = shapes;
            return out;
        }
    }
    static final int MAX_TEXT = 64 * 1024, MAX_AUTHORS = 6;
    /**
     * 一次检索请求的响应留档（A4）。三个数各说一件事，谁也不许替谁下结论：
     * <p>entries —— 本机从这一次响应里<b>解出</b>了几条候选；
     * <p>declaredTotal —— 源自己在响应里<b>声称</b>这一式命中几条。-1 是"这个源没说"，
     * 0 是"这个源说了没有"，两个数不许混成一个：知网与维普这个检索口实测不在响应里写总数
     * （2026-10-08 的 raw/step1 档里查不到任何 total 字段），万方写在 protobuf 字段 3。
     * <p>shape —— 这一份响应是什么形状：entries / declared-zero / blocked / js-shell /
     * json-empty / empty-body / empty-frame / frame-unparsed / unknown。0% 从此在档里
     * 一眼分得清是"源说没有"、"被挡"、还是"我们解不出"。
     */
    public static final class ShapeRow {
        public String engine = "", probe = "", shape = "", excerpt = "", error = "";
        public int status = -1, bodyBytes = -1, entries;
        public long declaredTotal = -1L;
        public long millis;
        /** 这一行是失败请求补的那一行：PaperSources 看得见响应，Lane 只看得见异常。 */
        public boolean failed;
    }

    /** 留档出口：一条请求一行，实现方自己负责同步（泳道是并行的）。 */
    public interface ShapeSink { void record(ShapeRow row); }

    /** 留档摘录的宽度：够认出"这是验证页 / 这是空帧 / 这是 count:0"，又不至于把一条报告撑爆。 */
    static final int SHAPE_EXCERPT_CHARS = 180;
    /** 挡人页的形状标记：命中一个就标成 blocked，不许让它伪装成"这一式真的零命中"。 */
    private static final String[] BLOCK_MARKERS = { "验证码", "安全验证", "滑动验证", "人机验证", "访问受限",
            "操作过于频繁", "请稍后再试", "Access Denied", "Forbidden", "Precondition Failed",
            "fault filter abort", "captcha", "cf-browser-verification" };
    /** 源自己说"没有"的标记：万方那 25 字节空帧解出来就是"检索结果为空"，维普的 SSR 载荷里是 records:[]。 */
    private static final String[] EMPTY_MARKERS = { "检索结果为空", "没有找到相关", "暂无相关", "records:[]",
            "\"records\":[]", "\"total\":0", "total\":0", "hitCount\":0", "noresults" };

    /* 下面四个数是 2026-10-09 对着真页面量的：opticsjournal.net 对三个不同文章链接回同一份
       15,999 字节的人机挑战页，把 script 里的代码当字读能"抽出"5,850 字，扔掉代码只剩 402 个可读字
       （占页面字符数的 2.5%）。宁可回"这篇没正文"，也不许拿壳页冒充正文进比对基线。 */
    /** 只挡"一句话壳页"：比包内最短的一份真全文夹具（38 字）还短，不可能是正文。 */
    static final int MIN_BODY_CHARS = 20;
    /** 带防爬标记的响应只在这个数以下才算壳页；再长就当正文，免得误杀真讲"人机验证"的论文。 */
    static final int BLOCKED_BODY_CHARS = 2000;
    /** 页面本身够大才谈得上"内容与字节对不上"这条判据。 */
    static final int SHELL_BODY_CHARS = 4000;
    /** 一大页字节只解出这么点可读字 → 壳页（没有标记的那一类，比如纯 JS 跳转页）。 */
    static final int SHELL_READABLE_CHARS = 1000;
    /** 防爬/人机验证壳页的标记。shapeOf 里那一份只在"一个字都没解出来"时才看，这里两份都要看。 */
    private static final String[] CHALLENGE_MARKERS = { "CF_APP_WAF", "cf-chl", "cf-browser-verification",
            "just a moment", "verify you are human", "challenge-platform", "人机验证", "安全验证",
            "滑动验证", "访问受限", "access denied", "precondition failed", "captcha" };

    /**
     * 这一页是来给内容的还是来挡人的。标记和可读字数一起看：只看标记会把"正文里恰好有人机验证"
     * 这类论文误杀，只看字数会把 5,850 字的防爬页当正文放进来。两个数各挡一半，实测刚好错开。
     * 回 null 是"当正文读"，否则回留在 shape 里的那两个字。
     */
    static String challengeOf(String body, int readableChars) {
        if (readableChars >= BLOCKED_BODY_CHARS) return null;
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        for (String marker : CHALLENGE_MARKERS)
            if (lower.contains(marker.toLowerCase(Locale.ROOT))) return "blocked";
        if (readableChars < MIN_BODY_CHARS) return "thin";
        int bodyChars = body == null ? 0 : body.length();
        return bodyChars >= SHELL_BODY_CHARS && readableChars <= SHELL_READABLE_CHARS ? "thin" : null;
    }

    /** 文本响应的形状。它回答的是"这一页是来给内容的、来挡人的、还是明说没有"。 */
    static String shapeOf(String body, int entries, long declaredTotal) {
        String text = body == null ? "" : body;
        if (entries > 0) return "entries";
        if (text.trim().isEmpty()) return "empty-body";
        String lower = text.toLowerCase(Locale.ROOT);
        for (String marker : BLOCK_MARKERS)
            if (text.contains(marker) || lower.contains(marker.toLowerCase(Locale.ROOT))) return "blocked";
        if (declaredTotal == 0L) return "declared-zero";
        for (String marker : EMPTY_MARKERS)
            if (text.contains(marker) || lower.contains(marker.toLowerCase(Locale.ROOT))) return "declared-zero";
        String head = lower.length() <= 4096 ? lower : lower.substring(0, 4096);
        if (head.contains("<html") || head.contains("<!doctype") || head.contains("__nuxt__")
                || head.contains("<script")) return "js-shell";
        String trim = text.trim();
        if (trim.startsWith("{") || trim.startsWith("[")) return "json-empty";
        return "unknown";
    }

    /**
     * 二进制响应（万方 gRPC-web）按帧记账，不假装它是文本：帧里有几条著录项、解出几条、源声称几条。
     * 实测的"检索结果为空"就是 records=0 且 total=0 的 25 字节帧，那一行的形状必须是 declared-zero。
     */
    static String frameShape(int records, int entries, long declaredTotal, String excerpt) {
        if (entries > 0) return "entries";
        if (declaredTotal == 0L) return "declared-zero";
        String text = excerpt == null ? "" : excerpt.toLowerCase(Locale.ROOT);
        for (String marker : EMPTY_MARKERS)
            if (text.contains(marker.toLowerCase(Locale.ROOT))) return "declared-zero";
        if (records > 0) return "frame-unparsed";
        return "frame-empty";
    }

    /** 响应开头的可读摘录：控制字符一律换成空格，没有文本体就拿 UTF-8 硬解字节，只用于认形状。 */
    static String excerptOf(byte[] raw, String body) {
        String text = body == null ? "" : body;
        if (text.trim().isEmpty() && raw != null && raw.length > 0)
            text = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(Math.min(SHAPE_EXCERPT_CHARS + 8, text.length()));
        int taken = 0;
        for (int i = 0; i < text.length() && taken < SHAPE_EXCERPT_CHARS; i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t' || Character.isISOControl(c)) c = ' ';
            out.append(c);
            taken++;
        }
        if (text.length() > taken) out.append('…');
        return out.toString().replaceAll(" {2,}", " ").trim();
    }

    /** 落一行留档。留档不许把检索带下水：出口自己抛的东西一律咽掉。 */
    private static void recordShape(Limits limits, String engine, String probe, ApiClient.Response response,
                                    int entries, long declaredTotal, String shape, String error, boolean failed) {
        ShapeSink sink = limits == null ? null : limits.shapes;
        if (sink == null) return;
        ShapeRow row = new ShapeRow();
        row.engine = engine == null ? "" : engine;
        row.probe = clipProbe(probe);
        row.status = response == null ? -1 : response.status;
        row.bodyBytes = response == null || response.raw == null ? -1 : response.raw.length;
        row.entries = Math.max(0, entries);
        row.declaredTotal = declaredTotal;
        row.shape = shape == null ? "" : shape;
        row.excerpt = response == null ? "" : excerptOf(response.raw, response.body);
        row.millis = response == null ? 0L : response.elapsedMillis;
        row.error = error == null ? "" : error;
        row.failed = failed;
        try { sink.record(row); } catch (RuntimeException ignored) { }
    }

    /** 检索式在留档里只留一行认得出的短摘要，别把整扇窗口原文塞进报告存档。 */
    private static String clipProbe(String value) {
        String text = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }

    /**
     * 源在响应里自己声明的命中总数；拿不到一律 -1（=没说）。万方不走这里（在 envelope 里），
     * arXiv 是 XML，其余 JSON 族各认自己那一个计数字段。
     */
    private static long declaredTotal(String name, Object root) {
        try {
            if (name.equals("arxiv")) return digits(Xml.text(String.valueOf(root), "totalResults"));
            String[] paths = name.equals("openalex") ? new String[] { "meta.count" }
                    : name.equals("crossref") ? new String[] { "message.total-results" }
                    : name.equals("semantic-scholar") ? new String[] { "total" }
                    : name.equals("europepmc") ? new String[] { "hitCount" }
                    : name.equals("core") ? new String[] { "resultsSize", "total" }
                    : new String[0];
            for (String path : paths) {
                Object value = ApiJson.path(root, path);
                if (value instanceof Number) return (long) ((Number) value).doubleValue();
                if (value instanceof String) { long got = digits((String) value); if (got >= 0L) return got; }
            }
        } catch (RuntimeException ignored) {
            /* 计数字段读不动就当这个源没声明，绝不让留档这一路把一次好检索弄失败。 */
        }
        return -1L;
    }

    private static long digits(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > 15) return -1L;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) < '0' || text.charAt(i) > '9') return -1L;
        return Long.parseLong(text);
    }
    /**
     * 万方翻页游标的三条上限，三个数各管一件事，谁也不许替谁下结论（标定依据见
     * docs/retrieval-recall.md 瓶颈三，2026-10-08 经 127.0.0.1:7897 真接口实测）：
     * WANFANG_MAX_PAGES 是游标最多推到第几页，WANFANG_ZERO_PAGE_LIMIT 是连续几页
     * "按 URL 一条新的都没多"才算这个检索式取尽。取 3 的依据：同一检索式重复问 5 轮，
     * 第二轮起新增恒为 0（4/4 个检索式），所以阈值只要等于 2 就还是那把提前判死的尺；
     * 游标往前推的 36 次换页请求里 0 次出现"空页之后又有货"，可靠下限是 1，
     * 3 = 1（实测可靠性）+ 2（至少比现值宽一档，每页实测 125-530ms，多花两页买得起）。
     */
    static final int WANFANG_MAX_PAGES = 6, WANFANG_ZERO_PAGE_LIMIT = 3;
    /** 同源相邻两页之间的间隔：翻页等于把同一个源连问好几次，防封 IP 那把尺不许从翻页这条后门绕过去。 */
    static long wanfangPageGapMillis = 400L;
    private static final LinkedHashMap<String, String> ENDPOINTS = defaults();
    private PaperSources() { }

    private static LinkedHashMap<String, String> defaults() {
        LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();
        map.put("cnki", "https://search.cnki.com.cn/search/listresult");
        map.put("cqvip", "https://www.cqvip.com/search");
        map.put("wanfang", "https://s.wanfangdata.com.cn/SearchService.SearchService/search");
        map.put("ncpssd", "https://www.ncpssd.org/searchHandler/search");
        map.put("openalex", "https://api.openalex.org/works");
        map.put("crossref", "https://api.crossref.org/works");
        map.put("semantic-scholar", "https://api.semanticscholar.org/graph/v1/paper/search");
        map.put("europepmc", "https://www.ebi.ac.uk/europepmc/webservices/rest/search");
        map.put("arxiv", "https://export.arxiv.org/api/query");
    /* CORE answers the slash-less path with a 301, and this transport refuses redirects, so the
     * canonical trailing slash is part of the endpoint, not a cosmetic detail. */
    map.put("core", "https://api.core.ac.uk/v3/search/works/");
        return map;
    }
    public static ArrayList<String> engines() { return new ArrayList<String>(defaults().keySet()); }
    /** Display name for notes, progress and reports; the engine id itself stays lowercase. */
    public static String label(String engine) {
        String name = key(engine);
        if (name.equals("cnki")) return "知网（期刊与会议论文）";
        if (name.equals("cqvip")) return "维普（中文期刊）";
        if (name.equals("wanfang")) return "万方数据";
        if (name.equals("ncpssd")) return "国家哲社文献中心";
        if (name.equals("openalex")) return "OpenAlex";
        if (name.equals("crossref")) return "Crossref";
        if (name.equals("semantic-scholar")) return "Semantic Scholar";
        if (name.equals("europepmc")) return "Europe PMC";
        if (name.equals("arxiv")) return "arXiv";
        if (name.equals("core")) return "CORE";
        return name;
    }
    private static String key(String engine) { return engine == null ? "" : engine.trim().toLowerCase(Locale.ROOT); }

    /** Loopback fixtures replace the live endpoints during regression runs. */
    static void setEndpoint(String engine, String url) {
        String name = key(engine);
        if (!ENDPOINTS.containsKey(name)) throw new IllegalArgumentException("未知检索源");
        ENDPOINTS.put(name, url == null || url.trim().isEmpty() ? defaults().get(name) : url.trim());
    }
    static void resetEndpoints() { ENDPOINTS.clear(); ENDPOINTS.putAll(defaults()); }
    static String endpoint(String engine) {
        String value = ENDPOINTS.get(key(engine));
        return value == null ? defaults().get(key(engine)) : value;
    }

    public static ArrayList<Candidate> search(String engine, String query, Limits limits,
                                              ApiClient.Cancellation cancellation) throws IOException {
        Limits safe = limits == null ? new Limits() : limits;
        String name = key(engine);
        if (!defaults().containsKey(name)) throw new IllegalArgumentException("未知检索源");
        String phrase = query == null ? "" : query.trim();
        if (phrase.isEmpty()) throw new IllegalArgumentException("检索短语为空");
        if (name.equals("core") && safe.coreKey.trim().isEmpty()) throw new IllegalArgumentException("CORE 未配置 API Key");
        int per = Math.max(1, Math.min(safe.perEngine, 50));
        LinkedHashMap<String, String> headers = new LinkedHashMap<String, String>();
        if (name.equals("core")) headers.put("api-key", safe.coreKey.trim());
        /* 国家哲社文献中心只接 POST，检索式必须带字段码；维普的检索页是服务端渲染的 HTML。
           其余源仍是一次 GET 加查询串。 */
        /* 万方走 gRPC-web：请求体和响应都不是文本，编解码在 WanfangProtocol 里，
           这条路上没有查询串，检索式整个装在 protobuf 消息里发出去。 */
        if (name.equals("wanfang")) {
            return searchWanfang(phrase, per, safe, headers, cancellation);
        }
        /* 知网这个检索口只认 POST 表单，而且要看一眼浏览器样的请求头，回来的还是带高亮标签的 HTML，
           所以它不进下面那套 JSON 解析，整个交给 CnkiSearch。 */
        if (name.equals("cnki")) {
            ApiClient.Response page = HttpTransport.post(endpoint(name), CnkiSearch.form(phrase, 1),
                    CnkiSearch.headers(), safe.timeoutSeconds, HttpTransport.MAX_BODY, cancellation,
                    proxyFor(safe));
            try {
                ArrayList<Candidate> found = CnkiSearch.parse(page.body, per);
                /* 知网这个口不在响应里写命中总数，declaredTotal 就留 -1（=源没说），不替它编一个。 */
                recordShape(safe, name, phrase, page, found.size(), -1L,
                        shapeOf(page.body, found.size(), -1L), "", false);
                return found;
            } catch (RuntimeException error) {
                /* 解不出条目同样要留档，而且形状必须交给 shapeOf：挡人页就长在这一行里，
                   以前这里只把"无法解析"四个字抛上去，页面上写着"请输入验证码"也没人看得见。 */
                recordShape(safe, name, phrase, page, 0, -1L, shapeOf(page.body, 0, -1L), "响应无法解析", true);
                throw new IOException("知网检索响应无法解析");
            }
        }
        /* OpenAlex 这一路单独走：中文稿先按"更可能带正文"的收敛式问一次，一条都没回来才补问一次宽式。 */
        if (name.equals("openalex"))
            return searchOpenAlex(phrase, per, safe, headers, cancellation).found;
        ApiClient.Response response = name.equals("ncpssd")
                ? HttpTransport.post(endpoint(name), formFor(phrase, per), headers,
                        safe.timeoutSeconds, HttpTransport.MAX_BODY, cancellation, proxyFor(safe))
                : HttpTransport.get(endpoint(name) + "?" + queryFor(name, phrase, per),
                        headers, safe.timeoutSeconds, HttpTransport.MAX_BODY, cancellation, proxyFor(safe));
        Object root;
        try { root = name.equals("arxiv") || name.equals("cqvip") ? response.body : ApiJson.parse(response.body); }
        catch (RuntimeException error) {
            recordShape(safe, name, phrase, response, 0, -1L, shapeOf(response.body, 0, -1L),
                    "检索响应格式无效", true);
            throw new IOException("检索响应格式无效");
        }
        /* 源声称的总数要在解析条目之前拿：解不出条目那一路更需要这个数字（0 与"没声明"是两回事）。 */
        long declared = declaredTotal(name, root);
        ArrayList<Candidate> found;
        try {
            if (name.equals("openalex")) found = parseOpenAlex(root, per);
            else if (name.equals("crossref")) found = parseCrossref(root, per);
            else if (name.equals("semantic-scholar")) found = parseSemantic(root, per);
            else if (name.equals("europepmc")) found = parseEuropePmc(root, per);
            else if (name.equals("arxiv")) found = parseArxiv(String.valueOf(root), per);
            else if (name.equals("cqvip")) found = parseCqvip(String.valueOf(root), per);
            else if (name.equals("ncpssd")) found = parseNcpssd(root, per);
            else found = parseCore(root, per);
        } catch (RuntimeException error) {
            recordShape(safe, name, phrase, response, 0, declared, shapeOf(response.body, 0, declared),
                    "检索响应格式无效", true);
            throw new IOException("检索响应格式无效");
        }
        recordShape(safe, name, phrase, response, found.size(), declared,
                shapeOf(response.body, found.size(), declared), "", false);
        if (name.equals("europepmc")) recordNoAccessEvidence(safe, name, phrase, response, found);
        return found;
    }

    /**
     * 万方：一个检索式问到尽为止，但"要第几页""什么算重复""什么时候收摊"是三个独立的量，各管各的。
     *
     * <p>翻页游标 —— CommonRequest.currentPage（protobuf 字段 5）从 1 往后推。实测这个游标是活的：
     * 同一检索式连要四页，每页 12 条著录项，URL 两两零重合；而 2.1.0 之前这一路把页码写死成 1，
     * 一个检索式最多只拿得到第一页。
     * <p>页间去重 —— 只认 locator（文献页 URL），locator 空的退到题名；名次、分数、摘要像不像一律不参与，
     * 那是排序阶段的事，拿它去重等于把换页回来的同一条当新货。
     * <p>零新增阈值 —— 连续 WANFANG_ZERO_PAGE_LIMIT 页一条新的都没多，才认这个检索式的游标取尽。
     *
     * <p>"没货"有两种，不许混：整页连著录项都没带（实测是固定的 25 字节空帧，服务端明说这一式零命中，
     * 换页也不会再有，实测 18/18 次无一例外）就地收摊，一次配额也不许多花；页带了著录项但全是见过的，
     * 那才算进零新增那一串。
     */
    private static ArrayList<Candidate> searchWanfang(String phrase, int per, Limits safe,
                                                      Map<String, String> headers,
                                                      ApiClient.Cancellation cancellation) throws IOException {
        ArrayList<Candidate> found = new ArrayList<Candidate>();
        LinkedHashMap<String, Boolean> seen = new LinkedHashMap<String, Boolean>();
        int quietPages = 0;
        for (int page = 1; page <= WANFANG_MAX_PAGES; page++) {
            if (page > 1 && !waitBeforePage(cancellation)) break;
            ApiClient.Response binary;
            try {
                binary = HttpTransport.postBytes(endpoint("wanfang"),
                        WanfangProtocol.request(phrase, page, per), WanfangProtocol.CONTENT_TYPE, headers,
                        safe.timeoutSeconds, HttpTransport.MAX_BODY, cancellation, proxyFor(safe));
            } catch (IOException error) {
                /* 第一页失败就是这一路失败，照原话抛上去（限流补试、本次不可用都靠它）。
                   后面几页失败只该少拿几条，不该把已经到手题录一起作废。 */
                if (!found.isEmpty()) break;
                throw error;
            }
            Envelope envelope = envelopeOf(binary.raw);
            ArrayList<Candidate> frame = WanfangProtocol.parse(binary.raw, per);
            int added = 0;
            for (Candidate candidate : frame)
                if (keep(seen, candidate)) { found.add(candidate); added++; }
            /* 每一页都留一行：换页回来的空帧与第一页的空帧是两种不同的账，档里必须分得开。 */
            recordShape(safe, "wanfang", phrase, binary, frame.size(), envelope.total,
                    frameShape(envelope.records, frame.size(), envelope.total,
                            excerptOf(binary.raw, binary.body)), "", false);
            quietPages = added > 0 ? 0 : quietPages + 1;
            /* 这一式要下发的条数已经凑够，或服务端声称的命中总数已经取满：游标没必要再推。 */
            if (found.size() >= per) break;
            if (envelope.total >= 0 && found.size() >= envelope.total) break;
            if (envelope.records == 0) break;
            if (quietPages >= WANFANG_ZERO_PAGE_LIMIT) break;
        }
        return found;
    }

    /** 翻页之间的间隔与取消检查：返回 false 表示这一路该收摊，别再花配额。 */
    private static boolean waitBeforePage(ApiClient.Cancellation cancellation) {
        if (cancellation != null && cancellation.cancelled()) return false;
        long gap = Math.max(0L, wanfangPageGapMillis);
        if (gap > 0L) {
            try { Thread.sleep(gap); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
        }
        return cancellation == null || !cancellation.cancelled();
    }

    /** 一页响应的信封账目：帧里带了几条著录项，以及服务端声称这一式总共命中几条（没带这个字段就是 -1）。 */
    private static final class Envelope {
        int records;
        long total = -1L;
    }

    /**
     * 只读信封，不碰著录项。"这一页带了几条"和"我们能解出几条"必须是两个数：实测万方一页常混进
     * 我们编不出著录项的载荷类型（12 条里只解得出 9 条），把那种页当成"源没货"就是拿自己的解析缺口
     * 去判取尽。帧解不动时这里安静地交白卷，格式无效那笔账由 WanfangProtocol.parse 去报。
     */
    private static Envelope envelopeOf(byte[] raw) {
        Envelope envelope = new Envelope();
        int at = 0;
        try {
            while (raw != null && at + 5 <= raw.length) {
                int flags = raw[at] & 0xFF;
                long declared = ((long) (raw[at + 1] & 0xFF) << 24) | ((long) (raw[at + 2] & 0xFF) << 16)
                        | ((long) (raw[at + 3] & 0xFF) << 8) | (long) (raw[at + 4] & 0xFF);
                at += 5;
                if (declared < 0 || at + declared > raw.length) return envelope;
                int length = (int) declared;
                byte[] payload = java.util.Arrays.copyOfRange(raw, at, at + length);
                at += length;
                if ((flags & 0x80) != 0) continue;
                for (ProtoWire.Field field : ProtoWire.read(payload)) {
                    if (field.bytes != null) { if (field.number == 4) envelope.records++; continue; }
                    if (field.number == 3) envelope.total = field.varint;
                }
            }
        } catch (RuntimeException ignored) {
            /* 交白卷即可：帧长无效由 parse 那一路报，取尽判据不拿半截账目说话。 */
        }
        return envelope;
    }

    /** 页间去重只认这一把尺：URL 优先，空的退到题名；分数与名次不参与。 */
    private static boolean keep(LinkedHashMap<String, Boolean> seen, Candidate candidate) {
        String key = candidate.source.locator.isEmpty() ? candidate.source.title : candidate.source.locator;
        if (key.isEmpty()) return true;
        if (Boolean.TRUE.equals(seen.get(key))) return false;
        seen.put(key, Boolean.TRUE);
        return true;
    }

    /**

     * Turns the saved host:port into a proxy for the transport. Bracketed IPv6 literals lose
     * the brackets, which java.net.InetSocketAddress does not accept, and anything unusable
     * means "dial out directly" rather than a failed retrieval.
     */
    static java.net.Proxy proxyFor(Limits limits) {
        return Routes.parse(limits == null ? "" : limits.proxy);
    }

    /** Only the retrieval phrase travels in the query string, never document text. */
    static String queryFor(String engine, String phrase, int perEngine) throws IOException {
        String name = key(engine), query = encode(phrase);
        StringBuilder out = new StringBuilder();
        if (name.equals("openalex")) out.append(openAlexQuery(openAlexFilter(phrase), perEngine));
        else if (name.equals("crossref")) out.append("query.bibliographic=").append(query).append("&rows=").append(perEngine);
        else if (name.equals("semantic-scholar")) out.append("query=").append(query).append("&limit=").append(perEngine)
                .append("&fields=title,abstract,year,authors,externalIds,openAccessPdf");
        else if (name.equals("europepmc")) out.append("query=").append(query).append("&resultType=core&format=json&pageSize=").append(perEngine);
        else if (name.equals("arxiv")) out.append("search_query=all:").append(query).append("&start=0&max_results=").append(perEngine);
        /* 维普的服务端渲染只给第一页，page 类参数一律被忽略，所以这里不假装能翻页。 */
        else if (name.equals("cqvip")) out.append("k=").append(query);
        else out.append("q=").append(query).append("&limit=").append(perEngine);
        return out.toString();
    }
    /** 检索请求的落款邮箱：OpenAlex / Crossref 都要求匿名池里带上一个能找着我们的地址。 */
    static final String POLITE_MAILTO = "wordlite.checker@protonmail.com";

    /**
     * OpenAlex 这一路的检索词。它把 filter 里每个词都当成**必须命中**的条件，而中文一扇窗口在检索式里
     * 常常是一长串没有空格的连续字——实测本稿的 8 扇窗口按"前 5 段"问出去，命中数是 0/0/0/0/0/1/0/0
     * （2026-10-09，live），换成"取最前面那一段、不超过 16 字"是 6/18/0/13/626/…，其中带 pdf_url 的
     * 从 0 条变成每式 1-9 条。拉丁文照旧问 5 个词：那边一个词就是真空格隔开的一个词，砍到一个反而没方向。
     */
    static String openAlexTerm(String phrase) {
        String value = phrase == null ? "" : phrase.trim();
        if (value.isEmpty()) return "";
        return hasCjk(value) ? narrowing(value, 1, 16) : narrowing(value, 5, 60);
    }
    /**
     * 中文稿这一路的收敛条件。两个条件各自管一件事，实测在同一篇 19,967 字可比正文的稿子上
     * （2026-10-09，前 6 条检索式，per-page=12，经 127.0.0.1:7897，
     * {@code tools/recall-probe.ps1 -Probe OpenAlexFilterProbe}）：
     * <p>只勾 open_access.is_oa（旧写法）——回来 45 条，其中 20 条带 pdf_url，只有 6 条的 pdf
     * 落在自家解得开的白名单期刊官网，平均每篇 0.13 条，响应 1,043,678 字节；
     * <p>再加 language:zh + primary_location.source.has_issn:true——回来 13 条，10 条带 pdf_url，
     * 其中 9 条是白名单期刊官网（每篇 0.69 条，是旧写法的 5.3 倍），响应 75,611 字节（旧写法的 1/14）。
     * <p>代价写在上面：条目数从 45 掉到 13。OpenAlex 这一路在整机里的职责是"把可能带正文的中文条目
     * 问回来"，摘要级的广度仍由知网/万方/维普那几路负责。收敛式一条都没问回来的窗口补问一次宽式，
     * 那种窗口占实测 6 扇里的 3 扇。
     */
    static final String OPENALEX_BODY_FILTERS = ",language:zh,primary_location.source.has_issn:true";
    /** 补问那一遍的检索式挂的这个尾巴，只用于在留档里认出"这一行是补问"。 */
    static final String OPENALEX_BROAD_NOTE = "（宽式补问）";

    /** 这一扇窗口该用哪条 filter：中文稿走收敛式，拉丁文稿照旧只要求开放获取。 */
    static String openAlexFilter(String phrase) {
        String base = "title_and_abstract.search:" + openAlexTerm(phrase) + ",open_access.is_oa:true";
        return hasCjk(phrase) ? base + OPENALEX_BODY_FILTERS : base;
    }

    /** 补问用的宽式：与 2.3.0 出厂时那条完全一致，一个字都没动。 */
    static String openAlexBroadFilter(String phrase) {
        return "title_and_abstract.search:" + openAlexTerm(phrase) + ",open_access.is_oa:true";
    }

    /** 把 filter 拼成一次 GET 的查询串。mailto 是 OpenAlex polite pool 的规矩：不改返回内容，只是落款。 */
    static String openAlexQuery(String filter, int perEngine) throws IOException {
        return "filter=" + encode(filter) + "&per-page=" + perEngine
                + "&mailto=" + encode(POLITE_MAILTO)
                + "&select=id,doi,title,language,authorships,publication_year,open_access,"
                + "best_oa_location,abstract_inverted_index";
    }

    private static boolean hasCjk(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x3040 && c <= 0x9FFF) return true;
        }
        return false;
    }

    /**
     * 国家哲社文献中心的检索式必须写成带字段码的表达式，裸词一律返回 0 条。题名/关键词优先、摘要兜底，
     * 表达式自身的引号和括号从短语里去掉，免得检索式被检索词改写。
     */
    static String formFor(String phrase, int perEngine) throws IOException {
        String term = narrowing(phrase, 6, 30).replace('"', ' ').replace('(', ' ').replace(')', ' ').trim();
        if (term.isEmpty()) throw new IllegalArgumentException("检索短语为空");
        /* 整句加引号等于要求这几个字在原文里连排出现，知网之外没几个库受得住：实测"深度学习 图像分割
           综述"这样查回来是 0 条。拆成词、逐词加引号、再用 AND 连起来，才是这个检索口认的写法
           （同一组实测：单词 689 条，两个词 AND 6 条，整句 0 条，去引号则是一百四十万条噪声）。 */
        StringBuilder search = new StringBuilder();
        for (String token : term.split("\\s+")) {
            String one = token.trim();
            if (one.isEmpty()) continue;
            if (search.length() > 0) search.append(" AND ");
            search.append("(IKTE=\"").append(one).append("\" OR IKST=\"").append(one)
                    .append("\" OR IKRK=\"").append(one).append("\")");
        }
        if (search.length() == 0) throw new IllegalArgumentException("检索短语为空");
        return "pageNum=1&pageSize=" + perEngine + "&sType=0&search=" + encode(search.toString());
    }

    private static String encode(String value) throws IOException {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (UnsupportedEncodingException error) { throw new IOException("检索短语无法编码"); }
    }

    /* ---- 开放获取全文：一条链接背后可能是 HTML，也可能是一份 PDF ---- */

    /**
     * 自己建站发 PDF 的期刊官网。挑它们有两个理由：一是这些站直接给 PDF，不经跳转壳；
     * 二是实测命中率比聚合器高——2026-10-09 抽样 6 次成 4 次（其中机械工程学报那份 931,814 B，
     * 本机解出 18,977 字 / 14 页），而盲抓聚合平台 12 次只成 4 次。名单里没有的域名照样能下，
     * 只是排在它们后面、且必须走 HTTPS。
     */
    static final String[] OA_PDF_HOSTS = { "cjmenet.com.cn", "engine.scichina.com", "scichina.com",
            "sciengine.com", "front-sci.com", "viserdata.com", "opticsjournal.net", "hanspub.org", "jos.org.cn" };

    /** 一次全文抓取的结果与"没拿到字时它到底是什么"。 */
    static final class PdfFetch {
        byte[] bytes = new byte[0];
        String text = "";
        String shape = "not-tried";
        String error = "";
        String fonts = "";
        int status = -1, pages, glyphs, chars;
        boolean capped;
        long millis;
        /** 取回来之前跟了几跳跳转（下载口常常 302 到对象存储）。 */
        int hops;
    }

    /** Open-access full text, fetched only when the candidate really advertises it; failures yield "". */
    public static String fullText(Candidate candidate, Limits limits, ApiClient.Cancellation cancellation) throws IOException {
        if (candidate == null) return "";
        String url = candidate.fullTextUrl == null ? "" : candidate.fullTextUrl.trim();
        if (url.isEmpty()) return "";
        String engine = candidate.source == null || candidate.source.engine == null ? "" : candidate.source.engine;
        /* .pdf 结尾的链接以前是直接丢掉的，而 app 自己有 PDF 解析：开放获取源给的正文十有七八就是 .pdf，
           丢掉它等于把唯一能拿到中文正文的一路堵死。现在交给 PdfFile 解，解不出才回空。 */
        if (pdfLink(url)) {
            PdfFetch got = fetchPdf(url, limits, cancellation);
            recordFetch(limits, engine, url, got);
            return got.text;
        }
        try {
            ApiClient.Response response = HttpTransport.get(url, null,
                    limits == null ? 20 : limits.timeoutSeconds, HttpTransport.MAX_FULL_TEXT, cancellation,
                    proxyFor(limits));
            String body = response.body == null ? "" : response.body;
            if (binary(body)) {
                recordText(limits, engine, url, response, 0, "binary-body", "响应是二进制，没当正文读");
                return "";
            }
            /* 正文只取给人读的那部分：脚本与样式里的代码不是字，留着会把比对基线泡成乱码。 */
            String text = body.indexOf('<') >= 0 ? Xml.readable(body) : collapse(body);
            int chars = text.trim().length();
            String blocked = challengeOf(body, chars);
            if (blocked != null) {
                recordText(limits, engine, url, response, 0, "text-" + blocked,
                        chars + " chars / " + blocked);
                return "";
            }
            String shape = shapeOf(body, chars == 0 ? 0 : 1, -1L);
            recordText(limits, engine, url, response, chars,
                    chars == 0 ? "text-empty" : ("text-" + shape), chars + " chars");
            return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        } catch (IOException error) {
            recordFetchFailure(limits, engine, url, error);
            return "";
        } catch (RuntimeException error) {
            recordFetchFailure(limits, engine, url, error);
            return "";
        }
    }

    /**
     * 下一份 PDF 并抽出正文。每一步不成都不抛出去——全文抓取一次都不许拖垮整轮检测——
     * 但"为什么没字"必须留在 shape 里：pdf-no-text-layer 是扫描件，pdf-undecodable 是字体映射不出，
     * not-a-pdf 是链接挂羊头卖狗肉，fetch-failed 是这条路没通，形状不同用户能做的下一步也不同。
     */
    static PdfFetch fetchPdf(String url, Limits limits, ApiClient.Cancellation cancellation) {
        PdfFetch out = new PdfFetch();
        long began = System.currentTimeMillis();
        HttpTransport.Fetched got;
        try {
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("Accept", "application/pdf, application/octet-stream;q=0.8, */*;q=0.5");
            got = HttpTransport.getPdf(url, headers, seconds(limits), HttpTransport.MAX_PDF_BODY, cancellation,
                    proxyFor(limits), plainHttpAllowed(url));
        } catch (IOException error) {
            /* PDF 这一路以前把所有答复都压成 fetch-failed：实测 sioc-journal.cn 那个下载口回的是
               301（跳到 https 的同一条地址，跟过去是 500 / 3,134 B 的 HTML 错误页），账上却和
               "网断了"长一个样。状态能说明的地方就用状态。 */
            int status = error instanceof ApiClient.Failure ? ((ApiClient.Failure) error).status : -1;
            out.shape = fetchFailureShape(status);
            /* 状态也要留下：回执那句"要机构权限或登录才能下（HTTP 403）"全靠它。 */
            out.status = status;
            out.error = clipLine(error.getMessage(), 60);
            out.millis = System.currentTimeMillis() - began;
            return out;
        } catch (RuntimeException error) {
            /* 这一路接不住 ApiClient.Failure（它是 IOException），状态只可能藏在 cause 里。 */
            int status = error.getCause() instanceof ApiClient.Failure
                    ? ((ApiClient.Failure) error.getCause()).status : -1;
            out.shape = fetchFailureShape(status);
            out.status = status;
            out.error = clipLine(error.getMessage(), 60);
            out.millis = System.currentTimeMillis() - began;
            return out;
        }
        out.status = got.status;
        out.bytes = got.bytes == null ? new byte[0] : got.bytes;
        out.capped = got.capped;
        out.hops = got.hops;
        out.millis = got.millis > 0L ? got.millis : System.currentTimeMillis() - began;
        if (!isPdf(out.bytes)) {
            out.shape = out.capped ? "not-a-pdf-capped" : "not-a-pdf";
            out.error = out.bytes.length + "B 开头没有 %PDF-";
            return out;
        }
        try {
            PdfFile.Extracted parsed = PdfFile.extractText(out.bytes);
            out.pages = parsed.pages;
            out.glyphs = parsed.undecodableGlyphs;
            out.fonts = parsed.undecodableFonts;
            out.text = parsed.text.length() > MAX_TEXT ? parsed.text.substring(0, MAX_TEXT) : parsed.text;
            out.chars = out.text.trim().length();
            out.shape = parsed.textOps == 0 ? "pdf-no-text-layer"
                    : out.chars == 0 ? (parsed.undecodable ? "pdf-undecodable" : "pdf-no-text")
                    : parsed.undecodable ? "pdf-body-partial" : "pdf-body";
            if (out.capped || parsed.truncated) out.shape = out.shape + "-capped";
        } catch (IOException | RuntimeException error) {
            out.shape = "pdf-unreadable";
            out.error = clipLine(error.getMessage(), 60);
        }
        return out;
    }

    /** 一键下载用的公开入口：把一篇候选的 PDF 取回来（进自建库由 CorpusImport 那边接着做）。 */
    public static byte[] downloadPdf(String url, Limits limits, ApiClient.Cancellation cancellation)
            throws IOException {
        String link = url == null ? "" : url.trim();
        if (link.isEmpty()) throw new FetchFailure("这条候选没有全文链接", "no-link");
        PdfFetch got = fetchPdf(link, limits, cancellation);
        /* 只有真是 PDF 的东西才配往下进自建库。以前这里点名四个形状才抛，
           新加进来的 needs-entitlement / link-not-found / redirect-not-followed 全从缝里漏过去，
           把 0 字节当成"下载成功"交给 CorpusImport，回执就只剩一句"没下载到内容"——
           2026-10-09 真机那一屏"导入 0 篇，失败 4 个"就是这么来的：链接是 403 还是失效，
           屏幕上分辨不出来，用户没法决定下一步。是 PDF 但没文字层的不在这里拦，
           那种交给 CorpusImport 判成"无文字层"，档位说法归它。 */
        if (got.bytes.length == 0 || !isPdf(got.bytes)) throw new FetchFailure(describeFetch(got), got.shape);
        return got.bytes;
    }

    /** 下载口为什么没给来文件，带形状号：回执要能按形状分堆，不能只剩一句"没下载到内容"。 */
    public static final class FetchFailure extends IOException {
        public final String shape;
        public FetchFailure(String message, String shape) {
            super(message);
            this.shape = shape == null ? "" : shape;
        }
    }

    /** 抓回来这一份为什么没有字，一句能说清的话。 */
    static String describeFetch(PdfFetch got) {
        if (got == null) return "没试过这篇的全文链接";
        if (got.shape.equals("fetch-failed")) return "全文链接没打通" + (got.error.isEmpty() ? "" : "：" + got.error);
        if (got.shape.startsWith("not-a-pdf")) return "那个链接回来的不是 PDF";
        if (got.shape.equals("pdf-no-text-layer")) return "这份 PDF 是扫描版，没有文字层";
        if (got.shape.equals("pdf-unreadable")) return "这份 PDF 解不出页面结构" + (got.error.isEmpty() ? "" : "：" + got.error);
        if (got.shape.equals("pdf-undecodable")) return "这份 PDF 的字形映射读不出" + (got.fonts.isEmpty() ? "" : "：" + got.fonts);
        String status = got.status > 0 ? "（HTTP " + got.status + "）" : "";
        if (got.shape.equals("needs-entitlement")) return "要机构权限或登录才能下" + status;
        if (got.shape.equals("link-not-found")) return "这个链接已经打不开了" + status;
        if (got.shape.equals("paywalled")) return "这篇在付费墙后面" + status;
        if (got.shape.equals("throttled")) return "这个源在限流，过一会儿再下" + status;
        if (got.shape.equals("source-unavailable")) return "这个源自己答不上来" + status;
        if (got.shape.equals("redirect-not-followed")) return "这个链接一直往别处跳，跟不过去";
        return "这篇没抓到正文";
    }

    /**
     * 这个链接是不是真给 PDF。除了 .pdf 结尾，还认期刊 CMS 那几个下载口
     * （机械工程学报的 downloadArticleFile.do?attachType=PDF&id=27657 就是这一类）。
     */
    static boolean pdfLink(String url) {
        String value = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return false;
        String path = value;
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        if (path.endsWith(".pdf") || path.endsWith("/pdf")) return true;
        /* 玛格泰克 CMS 的下载口不带 .pdf 后缀：/CN/article/downloadArticleFile.do?attachType=PDF&id=27657 */
        if (value.contains("attachtype=pdf")) return true;
        return path.endsWith("downloadarticlefile.do");
    }
    /** 白名单站点允许走 http 取 PDF：实测机械工程官网的 PDF 只有 http 一条路。其余一律 HTTPS。 */
    static boolean plainHttpAllowed(String url) {
        String host = hostOf(url);
        if (host.isEmpty() || !url.trim().toLowerCase(Locale.ROOT).startsWith("http://")) return false;
        return hostedBy(host, OA_PDF_HOSTS);
    }
    /**
     * 这条全文链接值不值得先花额度：0 白名单站点、1 其它 HTTPS 直链、2 其它 http 直链、
     * 3 doi.org 跳转壳、4 量实拿不到文件的链接（XML/HTML 端点、渲染口、我们按规矩不下的 http）、9 没链接。
     * <p>4 这一档是 2026-10-09 量出来的，不是猜的：<code>https://pdf.hanspub.org/....pdf</code> 带回
     * 12,853 字，而 Europe PMC 的 <code>.../PMC<id>/fullTextXML</code> 六次全 404（143 B JSON 报错），
     * 以前这两条同为 1，只能靠 BM25 分胜负——fullTexts=6 的额度就是被六个这样的平手决定的。
     * <p>同一把尺还量了另外两条必死的：<code>europepmc.org/articles/PMC<id>?pdf=render</code> 五次全
     * 403（0 B，换浏览器 UA 与 Referer 照旧，另一条 ptpmcrender.fcgi 也 403），
     * <code>http://sioc-journal.cn/...attachType=PDF...</code> 被"检索源必须使用 HTTPS"挡下、
     * 同一句地址改 https 是 403。这三类都排到 4：既不占全文额度，也不该出现在
     * "挂着可直接下载的开放获取 PDF"那一屏里（DuplicateEngine 的门槛是 rank &lt;= 2）。
     */
    static int pdfUrlRank(String url) {
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) return 9;
        String host = hostOf(value);
        if (host.endsWith("doi.org") || host.endsWith("dx.doi.org")) return 3;
        if (deadDocLink(value)) return 4;
        if (hostedBy(host, OA_PDF_HOSTS)) return 0;
        if (!pdfLink(value) && markupPath(value)) return 4;
        return value.toLowerCase(Locale.ROOT).startsWith("https://") ? 1 : 2;
    }

    /**
     * 量实拿不到文件的两种链接。只收"实测过、有次数、有响应尺寸"的，不收看起来不像的。
     */
    static boolean deadDocLink(String url) {
        String value = url == null ? "" : url.trim();
        String flat = value.toLowerCase(Locale.ROOT);
        /* Europe PMC 文章页的"渲染"开关：那是给浏览器看的口子，对匿名程序一律 403。 */
        int query = flat.indexOf('?');
        if (query >= 0 && flat.substring(query).contains("pdf=render")) return true;
        if (flat.contains("ptpmcrender.fcgi")) return true;
        /* http 直链只有白名单站点放行（期刊自建站那几条）；白名单之外的我们根本不会去下，
           所以不能把它当成"可以下进自建库"的承诺。回环不算——回归测试就跑在回环 http 上。 */
        if (flat.startsWith("http://") && !plainHttpAllowed(value) && !loopbackHost(hostOf(value))) return true;
        return false;
    }

    private static boolean loopbackHost(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }

    /** 路径末段（查询串不算）明写 xml/html 的链接：Europe PMC 的 fullTextXML、期刊的 article.html 都在内。 */
    static boolean markupPath(String url) {
        String value = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        int cut = value.indexOf('?');
        if (cut >= 0) value = value.substring(0, cut);
        int slash = value.lastIndexOf('/');
        String last = slash < 0 ? value : value.substring(slash + 1);
        return last.endsWith(".xml") || last.endsWith(".html") || last.endsWith(".htm")
                || last.endsWith("fulltextxml") || last.endsWith("fulltexthtml");
    }
    private static boolean hostedBy(String host, String[] suffixes) {
        for (String suffix : suffixes)
            if (host.equals(suffix) || host.endsWith("." + suffix)) return true;
        return false;
    }
    static String hostOf(String url) {
        String value = url == null ? "" : url.trim();
        int scheme = value.indexOf("://");
        if (scheme < 0) return "";
        int end = value.length();
        for (int i = scheme + 3; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '/' || c == '?' || c == '#') { end = i; break; }
        }
        String host = value.substring(scheme + 3, end);
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        int port = host.indexOf(':');
        return (port < 0 ? host : host.substring(0, port)).toLowerCase(Locale.ROOT);
    }
    /** 留档用的链接尾巴：主机名 + 最后一段路径，够认出是哪篇，也不把整条带参数的链接摊进报告。 */
    static String linkLabel(String url) {
        String host = hostOf(url);
        String value = url == null ? "" : url.trim();
        int scheme = value.indexOf("://");
        String path = scheme < 0 ? value : value.substring(scheme + 3);
        int slash = path.indexOf('/');
        path = slash < 0 ? "" : path.substring(slash + 1);
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        int last = path.lastIndexOf('/');
        String tail = last < 0 ? path : path.substring(last + 1);
        if (tail.length() > 24) tail = tail.substring(tail.length() - 24);
        return tail.isEmpty() ? host : host + "/" + tail;
    }
    private static int seconds(Limits limits) {
        return limits == null || limits.timeoutSeconds <= 0 ? 20 : Math.min(limits.timeoutSeconds, 60);
    }
    private static String clipLine(String value, int max) {
        String text = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return text.length() <= max ? text : text.substring(0, max);
    }
    private static boolean isPdf(byte[] bytes) {
        if (bytes == null || bytes.length < 32) return false;
        int probe = Math.min(bytes.length, 1024);
        for (int i = 0; i + 5 <= probe; i++)
            if (bytes[i] == '%' && bytes[i + 1] == 'P' && bytes[i + 2] == 'D' && bytes[i + 3] == 'F'
                    && bytes[i + 4] == '-') return true;
        return false;
    }
    /** 一次全文抓取也要落一行（A4 的口径：成功也落）。没有字的那几行，就是"为什么这篇不可比"的凭据。 */
    private static void recordFetch(Limits limits, String engine, String url, PdfFetch got) {
        ShapeSink sink = limits == null ? null : limits.shapes;
        if (sink == null || got == null) return;
        ShapeRow row = new ShapeRow();
        row.engine = engine == null ? "" : engine;
        row.probe = clipProbe(linkLabel(url));
        row.status = got.status;
        row.bodyBytes = got.bytes.length;
        row.entries = got.chars > 0 ? 1 : 0;
        row.shape = got.shape;
        row.millis = got.millis;
        row.error = got.error == null ? "" : got.error;
        row.excerpt = clipLine(fetchNote(got), SHAPE_EXCERPT_CHARS);
        try { sink.record(row); } catch (RuntimeException ignored) { }
    }
    static String fetchNote(PdfFetch got) {
        StringBuilder out = new StringBuilder();
        out.append(got.chars).append(" chars").append(got.pages > 0 ? " / " + got.pages + " pages" : "");
        if (got.glyphs > 0) out.append(" / 读不出字形 ").append(got.glyphs).append(" 个");
        if (!got.fonts.isEmpty()) out.append("（").append(got.fonts).append("）");
        if (got.capped) out.append(" / 已到下载上限，只取回前 ").append(got.bytes.length).append(" B");
        return out.toString();
    }
    private static void recordText(Limits limits, String engine, String url, ApiClient.Response response,
                                   int chars, String shape, String note) {
        ShapeSink sink = limits == null ? null : limits.shapes;
        if (sink == null) return;
        ShapeRow row = new ShapeRow();
        row.engine = engine == null ? "" : engine;
        row.probe = clipProbe(linkLabel(url));
        row.status = response == null ? -1 : response.status;
        row.bodyBytes = response == null || response.raw == null ? -1 : response.raw.length;
        row.entries = chars > 0 ? 1 : 0;
        row.shape = shape;
        row.millis = response == null ? 0L : response.elapsedMillis;
        row.excerpt = clipLine(note, SHAPE_EXCERPT_CHARS);
        try { sink.record(row); } catch (RuntimeException ignored) { }
    }
    private static void recordFetchFailure(Limits limits, String engine, String url, Throwable error) {
        ShapeSink sink = limits == null ? null : limits.shapes;
        if (sink == null) return;
        ShapeRow row = new ShapeRow();
        row.engine = engine == null ? "" : engine;
        row.probe = clipProbe(linkLabel(url));
        ApiClient.Failure refusal = error instanceof ApiClient.Failure ? (ApiClient.Failure) error : null;
        row.shape = fetchFailureShape(refusal == null ? -1 : refusal.status);
        row.failed = true;
        row.error = clipLine(error == null ? "" : error.getMessage(), 60);
        try { sink.record(row); } catch (RuntimeException ignored) { }
    }

    /**
     * 抓取失败要带上源答了什么："这条链接是编的"(404)、"要权限或要 cookie"(403)、"要跳转才给"(301/302)、
     * "这会儿不行"(5xx)、"拨不上"(没有状态)。这五种以前都叫 fetch-failed，所以拿 pmcid 拼出来的假 404
     * 和一篇真挂着但要 cookie 的论文在账上长一个样，用户下一步该做什么也就分不出来。
     */
    static String fetchFailureShape(int status) {
        if (status == 404 || status == 410) return "link-not-found";
        if (status == 401 || status == 403) return "needs-entitlement";
        if (status == 402) return "paywalled";
        if (status == 429) return "throttled";
        if (status >= 300 && status < 400) return "redirect-not-followed";
        if (status >= 500) return "source-unavailable";
        return "fetch-failed";
    }

    /**
     * 源自己没说这篇有正文，就不去抓，但"为什么这条候选没去抓"要在同一本响应账里留下字：一次检索一行，
     * 写清几条候选是被证据挡下的。少了这一行，账上只剩"没抓"这个动作，分不清是不值得抓还是忘了抓。
     */
    private static void recordNoAccessEvidence(Limits limits, String engine, String phrase,
                                               ApiClient.Response response, ArrayList<Candidate> found) {
        ShapeSink sink = limits == null ? null : limits.shapes;
        if (sink == null || found == null) return;
        int missing = 0;
        for (int i = 0; i < found.size(); i++) {
            Candidate candidate = found.get(i);
            if (candidate != null && (candidate.fullTextUrl == null || candidate.fullTextUrl.trim().isEmpty()))
                missing++;
        }
        if (missing == 0) return;
        ShapeRow row = new ShapeRow();
        row.engine = engine == null ? "" : engine;
        row.probe = clipProbe(phrase);
        row.status = response == null ? -1 : response.status;
        row.bodyBytes = response == null || response.raw == null ? -1 : response.raw.length;
        row.entries = missing;
        row.shape = "no-open-access-evidence";
        row.error = clipLine(missing + " 条候选既没有 isOpenAccess=Y，也没有 documentStyle=pdf/xml 的全文链接，"
                + "不编造 URL，不占全文额度", SHAPE_EXCERPT_CHARS);
        try { sink.record(row); } catch (RuntimeException ignored) { }
    }
    private static boolean binary(String body) {
        int scan = Math.min(body.length(), 2048), odd = 0;
        for (int i = 0; i < scan; i++) { char c = body.charAt(i); if (c == 0 || c == 0xFFFD) odd++; }
        return odd > 8;
    }

    static void add(ArrayList<Candidate> out, Candidate candidate, int limit) {
        if (out.size() >= limit) return;
        if (candidate.source.title.isEmpty() && candidate.abstractText.isEmpty()) return;
        for (Candidate known : out) {
            String left = !candidate.source.locator.isEmpty() ? candidate.source.locator : candidate.source.title;
            String right = !known.source.locator.isEmpty() ? known.source.locator : known.source.title;
            if (!left.isEmpty() && left.equals(right)) return;
        }
        out.add(candidate);
    }
    private static List<?> listAt(Object root, String path) {
        Object value = ApiJson.path(root, path);
        return value instanceof List ? (List<?>) value : java.util.Collections.emptyList();
    }
    private static String text(Object node, String... paths) {
        for (String path : paths) {
            Object value = ApiJson.path(node, path);
            if (value instanceof String) { if (!((String) value).trim().isEmpty()) return ((String) value).trim(); }
            else if (value instanceof Number) return integer(value);
            else if (value instanceof List && !((List<?>) value).isEmpty()) {
                Object first = ((List<?>) value).get(0);
                if (first instanceof String && !((String) first).trim().isEmpty()) return ((String) first).trim();
            }
        }
        return "";
    }
    private static String joined(Object node, String listPath, String... paths) {
        ArrayList<String> names = new ArrayList<String>();
        for (Object item : listAt(node, listPath)) {
            String name = text(item, paths);
            if (name.isEmpty() || names.contains(name)) continue;
            names.add(name);
            if (names.size() >= MAX_AUTHORS) break;
        }
        StringBuilder out = new StringBuilder();
        for (String name : names) { if (out.length() > 0) out.append(", "); out.append(name); }
        if (names.size() >= MAX_AUTHORS) out.append(" et al.");
        return out.toString();
    }
    private static String joinedName(Object node, String listPath, String one, String two) {
        ArrayList<String> names = new ArrayList<String>();
        for (Object item : listAt(node, listPath)) {
            String first = text(item, one), second = text(item, two), name = first + (first.isEmpty() || second.isEmpty() ? "" : " ") + second;
            if (name.isEmpty() || names.contains(name)) continue;
            names.add(name);
            if (names.size() >= MAX_AUTHORS) break;
        }
        StringBuilder out = new StringBuilder();
        for (String name : names) { if (out.length() > 0) out.append(", "); out.append(name); }
        return out.toString();
    }
    private static String url(Object node, String... paths) {
        for (String path : paths) {
            String value = text(node, path);
            if (value.startsWith("https://") || value.startsWith("http://")) return value;
        }
        return "";
    }
    /** First url from a list whose key matches value, used for typed link arrays. */
    private static String urlFrom(Object node, String listPath, String kindPath, String want, String urlPath) {
        String need = want == null ? "" : want.toLowerCase(Locale.ROOT);
        for (Object item : listAt(node, listPath)) {
            String kind = text(item, kindPath).toLowerCase(Locale.ROOT);
            if (!need.isEmpty() && !kind.contains(need)) continue;
            String value = text(item, urlPath);
            if (value.startsWith("https://") || value.startsWith("http://")) return value;
        }
        return "";
    }
    private static String integer(Object value) {
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) return "";
            return String.valueOf((long) number);
        }
        return value == null ? "" : String.valueOf(value).trim();
    }
    static String year(String value) {
        for (int i = 0; i + 3 < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') continue;
            int digits = 0;
            while (i + digits < value.length() && value.charAt(i + digits) >= '0' && value.charAt(i + digits) <= '9') digits++;
            if (digits == 4) { int parsed = Integer.parseInt(value.substring(i, i + 4)); if (parsed >= 1500 && parsed <= 2200) return String.valueOf(parsed); }
            i += digits;
        }
        return "";
    }
    private static String collapse(String text) {
        StringBuilder out = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) { space = out.length() > 0; continue; }
            if (space && out.length() > 0) out.append(' ');
            space = false;
            out.append(c);
        }
        return out.toString();
    }

    private static final String CQVIP_ABSTRACT = "class=\"abstr\"";
    private static final java.util.regex.Pattern CQVIP_DOCUMENT =
            java.util.regex.Pattern.compile("href=\"/doc/([^\"?]+)");
    private static final java.util.regex.Pattern CQVIP_AUTHOR =
            java.util.regex.Pattern.compile("class=\"author-name[^\"]*\"[^>]*>(?s)(.*?)</a>");
    private static final java.util.regex.Pattern CQVIP_JOURNAL =
            java.util.regex.Pattern.compile("href=\"/journal/[^\"]*\"[^>]*>(?s)(.*?)</a>");
    private static final java.util.regex.Pattern CQVIP_YEAR =
            java.util.regex.Pattern.compile("(\\d{4})\\s*年");
    private static final java.util.regex.Pattern CQVIP_ISSUE =
            java.util.regex.Pattern.compile("第\\s*(\\d+)\\s*期");
    private static final java.util.regex.Pattern CQVIP_ORGAN =
            java.util.regex.Pattern.compile("href=\"/organization/[^\"]*\"[^>]*>(?s)(.*?)</a>");

    /**
     * 维普不给匿名程序留 API，检索结果是服务端渲染在 HTML 里的，服务端只给摘要、作者、刊名、年期和
     * 文献页地址——题名那个 {@code <a class="title">} 是空的，真题名由浏览器脚本从同一份响应的
     * window.__NUXT__ 里填。所以一次请求读两样东西：HTML 给摘要与年期，CqvipState 给真题名、出处与关键词。
     * 题名照万方那一路署成"题名《出处》"：出处跟着题名走，题名指纹才与万方那条同源记录对得上——
     * 两个话题共 8 个维普种子里，报告点名到那一篇的从 2 例涨到 7 例；剩下那例是刊名中途改过名，
     * 两个库写的出处不同，指纹自然不同。状态解不出（服务端改了结构）时照旧署成"《刊名》 年 期"，
     * 这一行仍带文献页地址，仍然能人工核到出处。
     */
    static ArrayList<Candidate> parseCqvip(String html, int limit) {
        LinkedHashMap<String, CqvipState.Record> state = CqvipState.parse(html);
        ArrayList<Candidate> out = new ArrayList<Candidate>();
        int cursor = 0, recordStart = 0;
        while (out.size() < limit) {
            int at = html.indexOf(CQVIP_ABSTRACT, cursor);
            if (at < 0) break;
            int open = html.indexOf('>', at), close = html.indexOf("</span>", at);
            if (open < 0 || close < 0) break;
            String head = html.substring(recordStart, at);
            cursor = recordStart = close;
            String document = lastMatch(CQVIP_DOCUMENT, head);
            String journal = Xml.stripTags(lastMatch(CQVIP_JOURNAL, head)).trim();
            String year = lastMatch(CQVIP_YEAR, head);
            String issue = lastMatch(CQVIP_ISSUE, head);
            Candidate candidate = new Candidate();
            candidate.source.engine = "cqvip";
            candidate.source.id = document;
            candidate.source.locator = document.isEmpty() ? "" : "https://www.cqvip.com/doc/" + document;
            candidate.source.authors = nameList(CQVIP_AUTHOR, head);
            candidate.source.year = year;
            CqvipState.Record known = CqvipState.find(state, document);
            String venue = known == null || known.venue.isEmpty() ? journal : known.venue;
            if (venue.isEmpty()) {
                /* 学位论文没有期刊链接，出处退到培养单位，报告里至少看得出是谁的学校。 */
                venue = Xml.stripTags(lastMatch(CQVIP_ORGAN, head)).replaceAll("^\\[[0-9]+]\\s*", "").trim();
            }
            String title = known == null ? "" : known.title;
            String edition = (year.isEmpty() ? "" : year + "年") + (issue.isEmpty() ? "" : "第" + issue + "期");
            if (title.isEmpty()) {
                /* 没有题名可署时才把"学位论文"顶在前面：那是唯一还能看出文献类型的地方。 */
                String banner = journal.isEmpty() && document.startsWith("degree") && !venue.isEmpty()
                        ? "学位论文 " + venue : venue;
                candidate.source.title = banner.isEmpty() ? "维普记录 " + document
                        : banner + (edition.isEmpty() ? "" : " " + edition);
            } else {
                candidate.source.title = venue.isEmpty() || title.contains(venue)
                        ? title : title + "《" + venue + "》";
            }
            /* 题名解得出才追加题录那几项：解不出就整个退回 0.7.3 的字节，服务端改结构时不留下半成品。 */
            String summary = Xml.stripTags(html.substring(open + 1, close)).trim();
            candidate.abstractText = clip(title.isEmpty() || summary.isEmpty() ? summary
                    : comparable(summary, title, candidate.source.authors, edition, known.keywords));
            add(out, candidate, limit);
        }
        return out;
    }

    /**
     * 题录自带的字也交进可比对文本，形状照知网那一路（CnkiSearch.abstractOf：摘要 + 题名 + 作者 + 出处）。
     * 两份真实检索页上对同一批记录逐条比：一篇多交 60~110 字，人均 +74.9 与 +89.3 字。但这批字换的是可比
     * 材料，不是新判据——照抄题名与照抄关键词两种稿子，实测在旧语料与新语料上都是 0 命中（现行判据要逐字
     * 长串或整句 Dice >= 0.50，标题行太短进不来）；同一份无关正文上旧新两版命中完全相同，没多一笔误报。
     */
    private static String comparable(String summary, String title, String authors, String edition, String keywords) {
        StringBuilder tail = new StringBuilder();
        String marks = keywords == null || keywords.isEmpty() ? "" : "关键词 " + keywords.replace(";", " ");
        for (String part : new String[]{ title, authors, edition, marks }) {
            String one = part == null ? "" : part.trim();
            if (one.isEmpty()) continue;
            if (tail.length() > 0) tail.append(' ');
            tail.append(one);
        }
        return tail.length() == 0 ? summary : summary + "\n" + tail;
    }

    /** 哲社中心的 ik_* 字段带着高亮标签，所以只读干净字段；摘要即 remark。 */
    private static ArrayList<Candidate> parseNcpssd(Object root, int limit) {
        ArrayList<Candidate> out = new ArrayList<Candidate>();
        for (Object item : listAt(root, "data.rows")) {
            String id = text(item, "id", "data_id");
            String page = text(item, "HtmlUrl");
            Candidate candidate = new Candidate();
            candidate.source.engine = "ncpssd";
            candidate.source.id = id;
            candidate.source.title = text(item, "title", "title_auto");
            candidate.source.authors = splitAuthors(text(item, "creator"));
            candidate.source.year = year(text(item, "years", "date"));
            candidate.source.locator = page.startsWith("http") ? page : "ncpssd:" + id;
            candidate.abstractText = clip(text(item, "remark"));
            add(out, candidate, limit);
        }
        return out;
    }

    /**
     * 作者写成"名字[机构序号]"用分号串起来，序号对读者没有意义；也有记录用空格而不是分号隔开中文
     * 姓名，所以中文串按空格再切一刀（拉丁姓名带空格是真名，不动）。
     */
    private static String splitAuthors(String creator) {
        ArrayList<String> names = new ArrayList<String>();
        for (String group : (creator == null ? "" : creator).split("[;；]")) {
            String cleaned = group.replaceAll("\\[[^\\]]*]", "").trim();
            if (cleaned.isEmpty()) continue;
            String[] parts = cleaned.matches("(?s).*[\\u4e00-\\u9fa5].*") ? cleaned.split("\\s+")
                    : new String[]{cleaned};
            for (String part : parts) {
                String name = part.trim();
                if (!name.isEmpty() && names.size() < MAX_AUTHORS && !names.contains(name)) names.add(name);
            }
        }
        return join(names);
    }

    private static String lastMatch(java.util.regex.Pattern pattern, String text) {
        java.util.regex.Matcher matcher = pattern.matcher(text);
        String out = "";
        while (matcher.find()) out = matcher.group(1);
        return out == null ? "" : out.trim();
    }

    private static String nameList(java.util.regex.Pattern pattern, String text) {
        java.util.regex.Matcher matcher = pattern.matcher(text);
        ArrayList<String> names = new ArrayList<String>();
        while (matcher.find() && names.size() < MAX_AUTHORS) {
            /* 学位论文的那一个链接里串着"作者 • 导师 • 培养单位"，只有第一段是作者。 */
            String name = Xml.stripTags(matcher.group(1)).replaceAll("\\s+", " ").split("•")[0].trim();
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }
        return join(names);
    }

    static String join(ArrayList<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(", ");
            out.append(value);
        }
        return out.toString();
    }

    /** 一次 OpenAlex 提问的结果：解出的条目、源声称的总数、这一行是不是补问那一遍。 */
    private static final class OpenAlexRound {
        final ArrayList<Candidate> found = new ArrayList<Candidate>();
        long declared = -1L;
        boolean broad;
    }

    /**
     * OpenAlex 的取数：一次收敛式，收敛式空手而归时补问一次宽式，两次之间按 URL/题名去重。
     *
     * <p>为什么不是"每次问两遍"：一次整轮检索共享 MAX_REQUESTS=120 次提问，实测这一轮本就顶到上限
     * （2026-10-09 真跑：120 次问满，还剩 2 扇窗口没问）。补问只留给"收敛式一条都没回来"的窗口——
     * 那种窗口不补就是零覆盖，补一次最多花掉一次请求。
     * <p>为什么补问排在后面而不是替换：收敛式回来的条目带白名单期刊直链的比例是宽式的 5.3 倍
     * （见 OPENALEX_BODY_FILTERS 上面那笔账），两遍的条目都进候选池，谁先花全文额度由
     * CandidateRanker 按相似度与链接可下载性定，不在这里定。
     */
    private static OpenAlexRound searchOpenAlex(String phrase, int per, Limits safe,
                                                Map<String, String> headers,
                                                ApiClient.Cancellation cancellation) throws IOException {
        OpenAlexRound first = askOpenAlex(openAlexFilter(phrase), phrase, per, safe, headers, cancellation, false);
        if (!hasCjk(phrase) || !first.found.isEmpty()) return first;
        OpenAlexRound broad;
        try {
            broad = askOpenAlex(openAlexBroadFilter(phrase), phrase + OPENALEX_BROAD_NOTE,
                    per, safe, headers, cancellation, true);
        } catch (IOException error) {
            /* 补问那一路挂了不许把收敛式已经问回来的东西一起拖走：那几条是这一扇窗口唯一有的货。
               失败本身照样落一行，否则留档里只剩"这一式回来 1 条"，看不出还欠一次补问。 */
            recordShape(safe, "openalex", phrase + OPENALEX_BROAD_NOTE, null, 0, -1L, "fetch-failed",
                    clipLine(error.getMessage(), 60), true);
            return first;
        } catch (RuntimeException error) {
            recordShape(safe, "openalex", phrase + OPENALEX_BROAD_NOTE, null, 0, -1L, "fetch-failed",
                    clipLine(error.getMessage(), 60), true);
            return first;
        }
        /* 合并成一份新结果：往 broad.found 里加会把宽式那一批数两遍（真跑过一次就露馅）。 */
        OpenAlexRound merged = new OpenAlexRound();
        merged.broad = true;
        merged.found.addAll(dedupInto(first.found, broad.found));
        merged.declared = first.declared < 0L ? broad.declared
                : (broad.declared < 0L ? first.declared : Math.max(first.declared, broad.declared));
        return merged;
    }

    /** 把补问那一批并进收敛式那一批，同一条（URL 优先、空则题名）只留收敛式那一份。 */
    private static ArrayList<Candidate> dedupInto(ArrayList<Candidate> kept, ArrayList<Candidate> extra) {
        LinkedHashMap<String, Boolean> seen = new LinkedHashMap<String, Boolean>();
        ArrayList<Candidate> out = new ArrayList<Candidate>(kept);
        for (int i = 0; i < kept.size(); i++) keep(seen, kept.get(i));
        for (int i = 0; i < extra.size(); i++) if (keep(seen, extra.get(i))) out.add(extra.get(i));
        return out;
    }

    /** 一次提问：发出去、解条目、落一行留档。响应格式解不动照旧抛 IOException，让上层记这个源不可用。 */
    private static OpenAlexRound askOpenAlex(String filter, String probe, int per, Limits safe,
                                             Map<String, String> headers,
                                             ApiClient.Cancellation cancellation,
                                             boolean broad) throws IOException {
        OpenAlexRound round = new OpenAlexRound();
        round.broad = broad;
        ApiClient.Response response = HttpTransport.get(endpoint("openalex") + "?" + openAlexQuery(filter, per),
                headers, safe.timeoutSeconds, HttpTransport.MAX_BODY, cancellation, proxyFor(safe));
        Object root;
        try { root = ApiJson.parse(response.body); }
        catch (RuntimeException error) {
            recordShape(safe, "openalex", probe, response, 0, -1L,
                    shapeOf(response.body, 0, -1L), "检索响应格式无效", true);
            throw new IOException("检索响应格式无效");
        }
        round.declared = declaredTotal("openalex", root);
        try { round.found.addAll(parseOpenAlex(root, per)); }
        catch (RuntimeException error) {
            recordShape(safe, "openalex", probe, response, 0, round.declared,
                    shapeOf(response.body, 0, round.declared), "检索响应格式无效", true);
            throw new IOException("检索响应格式无效");
        }
        recordShape(safe, "openalex", probe, response, round.found.size(), round.declared,
                shapeOf(response.body, round.found.size(), round.declared), "", false);
        return round;
    }

    private static ArrayList<Candidate> parseOpenAlex(Object root, int limit) {
        ArrayList<Candidate> out = new ArrayList<Candidate>();
        for (Object item : listAt(root, "results")) {
            Candidate candidate = new Candidate();
            candidate.source.engine = "openalex";
            String doi = text(item, "doi"), id = text(item, "id");
            candidate.source.id = id;
            candidate.source.title = text(item, "title");
            candidate.source.authors = joined(item, "authorships", "author.display_name");
            candidate.source.year = year(text(item, "publication_year"));
            candidate.source.locator = first(doi, id);
            candidate.abstractText = clip(inverted(ApiJson.path(item, "abstract_inverted_index")));
            /* 以前先取 open_access.oa_url，它实测常常是 https://doi.org/... ——一条要跳转两次的链接，
               而传输层不追跳转，抓回来是 2,733 B 的跳转页、0 字。现在按"能不能真下到文件"排：
               白名单期刊官网 > 其它 HTTPS 直链 > 其它 http > doi.org 跳转壳。 */
            candidate.fullTextUrl = betterFullTextUrl(url(item, "best_oa_location.pdf_url"),
                    url(item, "best_oa_location.landing_page_url"), url(item, "open_access.oa_url"));
            add(out, candidate, limit);
        }
        return out;
    }

    /** 在几条候选链接里挑一条真能下到东西的：先比 rank，同级保持给出顺序（确定性）。 */
    static String betterFullTextUrl(String... candidates) {
        String best = "";
        int bestRank = 9;
        for (String value : candidates) {
            String url = value == null ? "" : value.trim();
            if (url.isEmpty()) continue;
            int rank = pdfUrlRank(url);
            if (rank < bestRank) { bestRank = rank; best = url; }
        }
        return best;
    }

    private static ArrayList<Candidate> parseCrossref(Object root, int limit) {
        ArrayList<Candidate> out = new ArrayList<Candidate>();
        for (Object item : listAt(root, "message.items")) {
            Candidate candidate = new Candidate();
            candidate.source.engine = "crossref";
            String doi = text(item, "DOI", "ID");
            candidate.source.id = doi;
            candidate.source.title = Xml.stripTags(text(item, "title", "original-title", "DOI"));
            candidate.source.authors = joinedName(item, "author", "given", "family");
            candidate.source.year = year(text(item, "issued.date-parts.0.0", "issued.date-time", "created.date-time"));
            candidate.source.locator = doi;
            candidate.abstractText = clip(Xml.stripTags(text(item, "abstract")));
            if (openAccess(item))
                candidate.fullTextUrl = first(urlFrom(item, "link", "content-type", "application/pdf", "URL"),
                        url(item, "resource.primary.URL"));
            add(out, candidate, limit);
        }
        return out;
    }
    private static boolean openAccess(Object item) {
        Object flag = ApiJson.path(item, "open-access.is_oa");
        if (flag instanceof Boolean) return (Boolean) flag;
        if (flag instanceof Number) return ((Number) flag).doubleValue() != 0;
        String status = text(item, "open-access.status");
        return status.equalsIgnoreCase("open") || status.equalsIgnoreCase("accepted") || status.equalsIgnoreCase("submitted");
    }

    private static ArrayList<Candidate> parseSemantic(Object root, int limit) {
        ArrayList<Candidate> out = new ArrayList<Candidate>();
        for (Object item : listAt(root, "data")) {
            Candidate candidate = new Candidate();
            candidate.source.engine = "semantic-scholar";
            String paper = text(item, "paperId"), doi = text(item, "externalIds.DOI"), arxiv = text(item, "externalIds.ArXiv");
            candidate.source.id = paper;
            candidate.source.title = text(item, "title");
            candidate.source.authors = joined(item, "authors", "name");
            candidate.source.year = year(text(item, "year"));
            candidate.source.locator = first(first(doi, arxiv.isEmpty() ? "" : "arXiv:" + arxiv), paper);
            candidate.abstractText = clip(Xml.stripTags(text(item, "abstract")));
            candidate.fullTextUrl = url(item, "openAccessPdf.url");
            add(out, candidate, limit);
        }
        return out;
    }

    private static ArrayList<Candidate> parseEuropePmc(Object root, int limit) {
        ArrayList<Candidate> out = new ArrayList<Candidate>();
        for (Object item : listAt(root, "resultList.result")) {
            Candidate candidate = new Candidate();
            candidate.source.engine = "europepmc";
            String pmid = text(item, "pmid"), pmcid = text(item, "pmcid"), doi = text(item, "doi");
            candidate.source.id = first(pmid, text(item, "id"));
            candidate.source.title = text(item, "title");
            candidate.source.authors = joinedName(item, "authorList.author", "firstName", "lastName");
            candidate.source.year = year(text(item, "pubYear", "bookOrReportDetails.pubYear"));
            candidate.source.locator = first(first(doi, pmid.isEmpty() ? "" : "PMID:" + pmid), pmcid);
            candidate.abstractText = clip(Xml.stripTags(text(item, "abstractText")));
            /* 全文链接只认响应自己给的证据，不替源编一条。以前这里拿 pmcid 拼 <base>/<source>/<pmcid>/fullTextXML，
               MED 行就成了 .../rest/MED/PMC13522914/fullTextXML，而 Europe PMC 没有这条路由：2026-10-09 经
               127.0.0.1:7897 实测两种拼法共六次，全 404 / 143 B 的 JSON 报错，其中一条既是 isOpenAccess=Y
               又是 inEPMC=Y 也照样 404。六个全文额度里有五个烧在这种假链接上，抓回来的 JSON 报错在账上
               又只长成"抓到空正文"，于是 0.13% 那种数能被当成比过。现在要的是源自己写的两样东西：
               isOpenAccess=Y 当门，fullTextUrlList 里 documentStyle=pdf（其次 xml）的那条当链接。
               只有 documentStyle=html 的不算数——实测那两条一条要追 301（传输层不追跳转）、一条 403/5,485 B。 */
            boolean open = text(item, "isOpenAccess").equalsIgnoreCase("Y");
            String pdfStyle = urlFrom(item, "fullTextUrlList.fullTextUrl", "documentStyle", "pdf", "url");
            String xmlStyle = urlFrom(item, "fullTextUrlList.fullTextUrl", "documentStyle", "xml", "url");
            candidate.fullTextUrl = open ? first(pdfStyle, xmlStyle) : "";
            add(out, candidate, limit);
        }
        return out;
    }
    private static ArrayList<Candidate> parseArxiv(String xml, int limit) {
        ArrayList<Candidate> out = new ArrayList<Candidate>();
        for (String entry : Xml.elements(xml, "entry")) {
            Candidate candidate = new Candidate();
            candidate.source.engine = "arxiv";
            String id = Xml.text(entry, "id");
            candidate.source.id = id.substring(id.lastIndexOf('/') + 1);
            candidate.source.title = Xml.text(entry, "title");
            candidate.source.authors = authors(Xml.elements(entry, "author"), "name");
            candidate.source.year = year(first(Xml.text(entry, "published"), Xml.text(entry, "updated")));
            candidate.source.locator = id;
            candidate.abstractText = clip(Xml.text(entry, "summary"));
            candidate.fullTextUrl = Xml.attribute(entry, "link", "href", "title", "pdf");
            add(out, candidate, limit);
        }
        return out;
    }
    private static ArrayList<Candidate> parseCore(Object root, int limit) {
        ArrayList<Candidate> out = new ArrayList<Candidate>();
        for (Object item : listAt(root, "results")) {
            Candidate candidate = new Candidate();
            candidate.source.engine = "core";
            String doi = text(item, "identifiers.doi", "doi"), id = text(item, "coreId", "id");
            candidate.source.id = id;
            candidate.source.title = text(item, "title");
            candidate.source.authors = joined(item, "authors", "name");
            candidate.source.year = year(text(item, "yearPublished", "publishedDate", "year"));
            candidate.source.locator = first(doi, id.isEmpty() ? "" : "CORE:" + id);
            candidate.abstractText = clip(Xml.stripTags(first(text(item, "abstract"), text(item, "abstracts"))));
            candidate.fullTextUrl = first(urlFrom(item, "links", "type", "pdf", "uri"),
                    url(item, "fullTextUrl", "landingPageUri", "links.0.uri", "links.0.url"));
            add(out, candidate, limit);
        }
        return out;
    }

    private static String authors(List<?> nodes, String name) {
        StringBuilder out = new StringBuilder();
        for (Object node : nodes) {
            String raw = node == null ? "" : String.valueOf(node);
            String value = Xml.text(raw, name);
            if (value.isEmpty()) value = collapse(Xml.stripTags(raw));
            if (value.isEmpty() || out.toString().contains(value)) continue;
            if (out.length() > 0) out.append(", ");
            out.append(value);
            if (out.length() > 240) break;
        }
        return out.toString();
    }
    private static String first(String left, String right) { return left == null || left.isEmpty() ? (right == null ? "" : right) : left; }
    static String clip(String value) {
        String text = value == null ? "" : value;
        return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
    }
    /** OpenAlex stores abstracts as word -> positions; rebuild the reading order. */
    static String inverted(Object node) {
        if (!(node instanceof Map)) return "";
        Map<?, ?> map = (Map<?, ?>) node;
        int size = 0;
        for (Object positions : map.values()) {
            if (!(positions instanceof List)) continue;
            for (Object at : (List<?>) positions) {
                double value = number(at);
                if (value >= 0 && value + 1 > size) size = (int) (value + 1);
            }
        }
        if (size <= 0 || size > 20000) return "";
        String[] slots = new String[size];
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof List)) continue;
            for (Object at : (List<?>) entry.getValue()) {
                int index = (int) number(at);
                if (index >= 0 && index < size && slots[index] == null) slots[index] = String.valueOf(entry.getKey());
            }
        }
        StringBuilder out = new StringBuilder();
        for (String slot : slots) {
            if (slot == null) continue;
            if (out.length() > 0) out.append(' ');
            out.append(slot);
        }
        return out.toString();
    }
    private static double number(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); } catch (RuntimeException error) { return -1; }
    }

    static final String[] ENGLISH_STOPWORDS = { "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with",
            "by", "from", "as", "at", "is", "are", "was", "were", "be", "been", "being", "this", "that", "these",
            "those", "it", "its", "we", "our", "they", "their", "which", "who", "whom", "whose", "what", "when",
            "where", "how", "than", "then", "there", "here", "such", "can", "may", "might", "will", "would", "could",
            "should", "not", "no", "nor", "so", "if", "too", "very", "more", "most", "also", "into", "over",
            "under", "between", "within", "during", "per", "via", "using", "used", "based" };
    static final String[] CHINESE_STOPWORDS = { "通过", "由于", "因此", "所以", "但是", "而且", "并且", "或者", "以及",
            "本文", "我们", "他们", "可以", "能够", "需要", "进行", "具有", "关于", "对于", "其中", "这一", "那样",
            "一些", "这样", "目的", "结果", "表明", "方面", "情况", "基础", "进一步", "以上", "以及", "的", "了",
            "和", "与", "及", "或", "在", "是", "为", "于", "以", "之", "也", "有", "对", "从", "并", "很", "更",
            "最", "等", "被", "把", "都", "就", "而", "其", "此", "该", "着", "过", "个", "们", "呢", "吗", "吧",
            "啊", "使", "由", "到", "即", "者", "可", "能", "将", "已", "所", "各", "每", "给", "叫", "让", "向",
            "往", "跟", "据", "若", "虽", "却", "仍", "再", "又", "还", "只", "才", "要", "这", "那", "他", "她", "它" };
    private static final java.util.HashSet<String> ENGLISH = new java.util.HashSet<String>();
    private static final LinkedHashMap<Character, ArrayList<String>> CHINESE = new LinkedHashMap<Character, ArrayList<String>>();
    private static final String BOUNDARY = " \t\r\n,.:!?\"'`()[]{}<>|/*&@#$%^~/\u3002\uFF01\uFF1F\uFF1B\uFF0C\u3001\uFF1A\u300C\u300D\u300E\u300F\uFF08\uFF09\u3010\u3011\u3014\u3015\u201C\u201D\u2018\u2019\u300A\u300B\u3008\u3009\u2014\u2026\u00B7\u3000";
    static {
        for (String word : ENGLISH_STOPWORDS) ENGLISH.add(word);
        for (String word : CHINESE_STOPWORDS) {
            Character key = Character.valueOf(word.charAt(0));
            ArrayList<String> bucket = CHINESE.get(key);
            if (bucket == null) { bucket = new ArrayList<String>(); CHINESE.put(key, bucket); }
            int at = 0;
            while (at < bucket.size() && bucket.get(at).length() >= word.length()) at++;
            bucket.add(at, word);
        }
    }
    private static final class Fragment {
        final String text;
        final double weight;
        Fragment(String text, double weight) { this.text = text; this.weight = weight; }
    }

    /** Retrieval phrase built from the most distinctive stopword-free fragments, never the whole document. */
    /** OpenAlex reads every word of the filter as a required term, so a whole sentence can never
     *  match: measured on the live API, five words find 35 papers and the same phrase with a
     *  sixth finds none. The opening of a window is where the topic actually sits. */
    static String narrowing(String phrase, int maxWords, int maxChars) {
        String value = phrase == null ? "" : phrase.trim();
        if (value.isEmpty()) return "";
        int budget = maxChars <= 0 ? 60 : Math.min(maxChars, 160);
        int words = Math.max(1, maxWords);
        StringBuilder out = new StringBuilder();
        for (String token : value.split("\\s+")) {
            String word = token.trim();
            if (word.isEmpty()) continue;
            if (out.length() >= budget || words == 0) break;
            if (out.length() > 0) out.append(' ');
            int room = budget - out.length();
            out.append(word.length() <= room ? word : word.substring(0, room));
            words--;
        }
        return out.length() > 0 ? out.toString() : value.substring(0, Math.min(value.length(), budget));
    }

    public static String queryPhrase(String text, int maxChars) {
        int budget = maxChars <= 0 ? 96 : Math.min(maxChars, 400);
        String clean = collapse(text == null ? "" : text);
        if (clean.isEmpty()) return "";
        ArrayList<int[]> ranges = parts(clean);
        ArrayList<ArrayList<Fragment>> byPart = new ArrayList<ArrayList<Fragment>>();
        double[] scores = new double[ranges.size()];
        for (int i = 0; i < ranges.size(); i++) {
            ArrayList<Fragment> fragments = fragments(clean, ranges.get(i)[0], ranges.get(i)[1]);
            byPart.add(fragments);
            double score = 0;
            for (Fragment fragment : fragments) score += fragment.weight + 2 + 2 * latinWords(fragment.text);
            scores[i] = score;
        }
        boolean[] taken = new boolean[ranges.size()];
        double gathered = 0;
        for (int pick = 0; pick < ranges.size() && gathered < budget; pick++) {
            int best = -1;
            for (int i = 0; i < scores.length; i++) if (!taken[i] && (best < 0 || scores[i] > scores[best])) best = i;
            if (best < 0 || scores[best] <= 0) break;
            taken[best] = true;
            gathered += scores[best];
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < byPart.size(); i++) {
            if (!taken[i]) continue;
            for (Fragment fragment : byPart.get(i)) {
                if (fragment.text.isEmpty() || out.length() + 1 + fragment.text.length() > budget) continue;
                if (out.length() > 0) out.append(' ');
                out.append(fragment.text);
            }
        }
        if (out.length() > 0) return out.toString();
        String longest = "";
        for (int i = 0; i < ranges.size(); i++) {
            String value = clean.substring(ranges.get(i)[0], ranges.get(i)[1]).trim();
            if (value.length() > longest.length()) longest = value;
        }
        return longest.length() > budget ? longest.substring(0, budget) : longest;
    }
    /** Sentence-sized windows; long sentences are cut further so no window can smuggle a whole page. */
    private static ArrayList<int[]> parts(String text) {
        ArrayList<int[]> out = new ArrayList<int[]>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean hard = c == '\u3002' || c == '\uFF01' || c == '\uFF1F' || c == '\uFF1B' || c == '!' || c == '?'
                    || c == ';' || c == '\n' || c == '\r';
            if (!hard) continue;
            if (i > start) out.add(new int[]{ start, i });
            start = i + 1;
        }
        if (start < text.length()) out.add(new int[]{ start, text.length() });
        ArrayList<int[]> bounded = new ArrayList<int[]>();
        for (int[] range : out) {
            int at = range[0];
            while (range[1] - at > 240) { bounded.add(new int[]{ at, at + 240 }); at += 240; }
            if (range[1] > at) bounded.add(new int[]{ at, range[1] });
        }
        return bounded;
    }
    private static ArrayList<Fragment> fragments(String text, int start, int end) {
        ArrayList<Fragment> out = new ArrayList<Fragment>();
        int at = start, piece = start;
        while (at < end) {
            int stop = stopLength(text, at);
            if (stop > 0) {
                if (at > piece) keep(out, text.substring(piece, at));
                at += stop;
                piece = at;
                continue;
            }
            if (BOUNDARY.indexOf(text.charAt(at)) >= 0) {
                if (at > piece) keep(out, text.substring(piece, at));
                at++;
                piece = at;
                continue;
            }
            at++;
        }
        if (end > piece) keep(out, text.substring(piece, end));
        return out;
    }
    private static void keep(ArrayList<Fragment> out, String raw) {
        String value = raw.trim();
        if (value.isEmpty()) return;
        double weight = weight(value);
        if (weight < 4) return;
        if (value.length() > 80) value = value.substring(0, 80);
        out.add(new Fragment(value, weight(value)));
    }
    private static int stopLength(String text, int at) {
        char c = text.charAt(at);
        if (c < 128) {
            if (!letter(c)) return 0;
            if (at > 0 && letter(text.charAt(at - 1))) return 0;
            int end = at;
            while (end < text.length() && letter(text.charAt(end))) end++;
            String word = text.substring(at, end).toLowerCase(Locale.ROOT);
            return ENGLISH.contains(word) ? word.length() : 0;
        }
        ArrayList<String> bucket = CHINESE.get(Character.valueOf(c));
        if (bucket == null) return 0;
        for (String word : bucket) if (text.regionMatches(at, word, 0, word.length())) return word.length();
        return 0;
    }
    private static boolean letter(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }
    private static double weight(String value) {
        double out = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (letter(c) || c >= '0' && c <= '9') out += 0.6;
            else if (c > 127 && BOUNDARY.indexOf(c) < 0) out += 1;
        }
        return out;
    }
    private static int latinWords(String value) {
        int out = 0;
        boolean inside = false;
        for (int i = 0; i < value.length(); i++) {
            boolean now = letter(value.charAt(i));
            if (now && !inside) out++;
            inside = now;
        }
        return out;
    }

    /** Small nesting-aware label scanner for Atom and JATS payloads; no regular expressions. */
    static final class Xml {
        private Xml() { }
        static ArrayList<String> elements(String xml, String name) {
            ArrayList<String> out = new ArrayList<String>();
            if (xml == null || name == null || name.isEmpty()) return out;
            int at = 0;
            while (at < xml.length()) {
                int open = opening(xml, name, at);
                if (open < 0) break;
                int end = tagEnd(xml, open);
                if (end < 0) break;
                if (xml.charAt(end - 1) == '/') { at = end + 1; continue; }
                int close = matching(xml, name, end + 1);
                if (close < 0) break;
                out.add(xml.substring(end + 1, close));
                int next = xml.indexOf('>', close);
                if (next < 0) break;
                at = next + 1;
            }
            return out;
        }
        static String text(String xml, String name) {
            ArrayList<String> found = elements(xml, name);
            return found.isEmpty() ? "" : collapse(stripTags(found.get(0)));
        }
        /** href of the first tag whose key attribute matches value. */
        static String attribute(String xml, String tag, String wanted, String key, String value) {
            if (xml == null) return "";
            int at = 0;
            while (at < xml.length()) {
                int open = opening(xml, tag, at);
                if (open < 0) return "";
                int end = tagEnd(xml, open);
                if (end < 0) return "";
                String head = xml.substring(open, end);
                at = end + 1;
                if (key != null && !attribute(head, key).equalsIgnoreCase(value)) continue;
                String found = attribute(head, wanted);
                if (!found.isEmpty()) return found;
            }
            return "";
        }
        static String attribute(String tagText, String name) {
            int at = 0;
            while (at < tagText.length()) {
                int start = tagText.indexOf(name, at);
                if (start < 0) return "";
                if (start > 0 && !Character.isWhitespace(tagText.charAt(start - 1))) { at = start + name.length(); continue; }
                int at2 = start + name.length();
                while (at2 < tagText.length() && (tagText.charAt(at2) == ' ' || tagText.charAt(at2) == '\t')) at2++;
                if (at2 >= tagText.length() || tagText.charAt(at2) != '=') { at = start + name.length(); continue; }
                at2++;
                while (at2 < tagText.length() && (tagText.charAt(at2) == ' ' || tagText.charAt(at2) == '\t')) at2++;
                if (at2 >= tagText.length()) return "";
                char quote = tagText.charAt(at2);
                if (quote == '"' || quote == '\'') {
                    int close = tagText.indexOf(quote, at2 + 1);
                    return close < 0 ? "" : unescape(tagText.substring(at2 + 1, close));
                }
                int close = at2;
                while (close < tagText.length() && !Character.isWhitespace(tagText.charAt(close))) close++;
                return unescape(tagText.substring(at2, close));
            }
            return "";
        }
        /**
         * HTML/XML 里给人读的那部分：<script>、<style>、<noscript>、<template>、<svg>、<iframe>
         * 连内容一起整块扔掉再拆标签。stripTags 只拆标签不扔内容，防爬页里的 JS 会被当成论文正文。
         * 标签没闭合就从那儿截断——后面的字节已经没法保证还是给人读的了。
         */
        static String readable(String html) {
            String value = html == null ? "" : html;
            String[] codeTags = { "script", "style", "noscript", "template", "svg", "iframe" };
            for (String tag : codeTags) {
                StringBuilder out = new StringBuilder(value.length());
                int at = 0;
                while (at < value.length()) {
                    int open = indexOfTag(value, at, tag, false);
                    if (open < 0) { out.append(value, at, value.length()); break; }
                    int head = value.indexOf('>', open);
                    if (head < 0) break;
                    int close = indexOfTag(value, head + 1, tag, true);
                    if (close < 0) break;
                    out.append(value, at, open).append(' ');
                    int end = value.indexOf('>', close);
                    at = end < 0 ? value.length() : end + 1;
                }
                value = out.toString();
            }
            return stripTags(value);
        }
        /** 找 <tag 或 </tag：名字必须整个对上，<styled-content 不算 <style。 */
        private static int indexOfTag(String value, int from, String name, boolean closing) {
            int at = Math.max(0, from);
            while (at < value.length()) {
                int found = value.indexOf('<', at);
                if (found < 0) return -1;
                int p = found + 1;
                boolean slash = p < value.length() && value.charAt(p) == '/';
                if (slash) p++;
                if (closing != slash) { at = found + 1; continue; }
                if (!value.regionMatches(true, p, name, 0, name.length())) { at = found + 1; continue; }
                int boundary = p + name.length();
                if (boundary >= value.length()) return found;
                char next = value.charAt(boundary);
                if (next == '>' || next == '/' || Character.isWhitespace(next)) return found;
                at = found + 1;
            }
            return -1;
        }
        static String stripTags(String value) {
            if (value == null) return "";
            StringBuilder out = new StringBuilder();
            int at = 0;
            while (at < value.length()) {
                char c = value.charAt(at);
                if (c == '<') {
                    int end = value.indexOf('>', at);
                    if (end < 0) break;
                    at = end + 1;
                    if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
                    continue;
                }
                out.append(c);
                at++;
            }
            return collapse(unescape(out.toString()));
        }
        static String unescape(String value) {
            if (value == null || value.indexOf('&') < 0) return value == null ? "" : value;
            StringBuilder out = new StringBuilder();
            int at = 0;
            while (at < value.length()) {
                char c = value.charAt(at);
                if (c != '&') { out.append(c); at++; continue; }
                int end = value.indexOf(';', at + 1);
                if (end < 0 || end - at > 12) { out.append(c); at++; continue; }
                String body = value.substring(at + 1, end);
                char single = body.equals("amp") ? '&' : body.equals("lt") ? '<' : body.equals("gt") ? '>'
                        : body.equals("quot") ? '"' : body.equals("apos") ? '\'' : body.equals("nbsp") ? ' ' : 0;
                if (single != 0) { out.append(single); at = end + 1; continue; }
                int code = 0;
                if (body.startsWith("#")) {
                    try {
                        boolean hex = body.startsWith("#x") || body.startsWith("#X");
                        code = Integer.parseInt(hex ? body.substring(2) : body.substring(1), hex ? 16 : 10);
                    } catch (NumberFormatException ignored) { code = 0; }
                }
                if (code > 0 && code <= Character.MAX_CODE_POINT) {
                    out.append(Character.toChars(code));
                    at = end + 1;
                    continue;
                }
                out.append(c);
                at++;
            }
            return out.toString();
        }
        private static int opening(String xml, String name, int from) {
            int at = from;
            while (true) {
                at = xml.indexOf('<' + name, at);
                if (at < 0) return -1;
                int after = at + name.length() + 1;
                if (after >= xml.length()) return -1;
                char next = xml.charAt(after);
                if (next == '>' || next == '/' || next == ' ' || next == '\t' || next == '\r' || next == '\n') return at;
                at = after;
            }
        }
        private static int closing(String xml, String name, int from) {
            int at = from;
            while (true) {
                at = xml.indexOf("</" + name, at);
                if (at < 0) return -1;
                int after = at + name.length() + 2;
                if (after >= xml.length()) return -1;
                char next = xml.charAt(after);
                if (next == '>' || next == ' ' || next == '\t' || next == '\r' || next == '\n') return at;
                at = after;
            }
        }
        private static int tagEnd(String xml, int from) {
            char quote = 0;
            for (int i = from + 1; i < xml.length(); i++) {
                char c = xml.charAt(i);
                if (quote != 0) { if (c == quote) quote = 0; continue; }
                if (c == '"' || c == '\'') quote = c;
                else if (c == '>') return i;
                else if (c == '<') return -1;
            }
            return -1;
        }
        private static int matching(String xml, String name, int from) {
            int depth = 1, at = from;
            while (at < xml.length()) {
                int close = closing(xml, name, at);
                if (close < 0) return -1;
                int open = opening(xml, name, at);
                if (open >= 0 && open < close) {
                    int end = tagEnd(xml, open);
                    if (end < 0) return -1;
                    if (xml.charAt(end - 1) != '/') depth++;
                    at = end + 1;
                    continue;
                }
                depth--;
                if (depth == 0) return close;
                int end = xml.indexOf('>', close);
                if (end < 0) return -1;
                at = end + 1;
            }
            return -1;
        }
    }
}

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
        if (name.equals("openalex")) out.append("filter=").append(encode("title_and_abstract.search:" + narrowing(phrase, 5, 60)))
                .append("&per-page=").append(perEngine)
                .append("&select=id,doi,title,authorships,publication_year,open_access,best_oa_location,abstract_inverted_index");
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

    /** Open-access full text, fetched only when the candidate really advertises it; failures yield "". */
    public static String fullText(Candidate candidate, Limits limits, ApiClient.Cancellation cancellation) throws IOException {
        if (candidate == null) return "";
        String url = candidate.fullTextUrl == null ? "" : candidate.fullTextUrl.trim();
        if (url.isEmpty() || pdf(url)) return "";
        try {
            ApiClient.Response response = HttpTransport.get(url, null,
                    limits == null ? 20 : limits.timeoutSeconds, HttpTransport.MAX_FULL_TEXT, cancellation,
                    proxyFor(limits));
            String body = response.body == null ? "" : response.body;
            if (binary(body)) return "";
            String text = body.indexOf('<') >= 0 ? Xml.stripTags(body) : collapse(body);
            return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        } catch (IOException ignored) { return ""; }
        catch (RuntimeException ignored) { return ""; }
    }
    private static boolean pdf(String url) {
        String path = url;
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        return path.toLowerCase(Locale.ROOT).endsWith(".pdf");
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
            candidate.fullTextUrl = url(item, "open_access.oa_url", "best_oa_location.pdf_url");
            add(out, candidate, limit);
        }
        return out;
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
        String base = restBase();
        for (Object item : listAt(root, "resultList.result")) {
            Candidate candidate = new Candidate();
            candidate.source.engine = "europepmc";
            String source = text(item, "source"), pmid = text(item, "pmid"), pmcid = text(item, "pmcid"), doi = text(item, "doi");
            candidate.source.id = first(pmid, text(item, "id"));
            candidate.source.title = text(item, "title");
            candidate.source.authors = joinedName(item, "authorList.author", "firstName", "lastName");
            candidate.source.year = year(text(item, "pubYear", "bookOrReportDetails.pubYear"));
            candidate.source.locator = first(first(doi, pmid.isEmpty() ? "" : "PMID:" + pmid), pmcid);
            candidate.abstractText = clip(Xml.stripTags(text(item, "abstractText")));
            boolean open = text(item, "isOpenAccess").equalsIgnoreCase("Y");
            String fullText = pmcid.isEmpty() ? "" : base + "/" + (source.isEmpty() ? "PMC" : source) + "/" + pmcid + "/fullTextXML";
            if (fullText.isEmpty())
                fullText = first(urlFrom(item, "fullTextUrlList.fullTextUrl", "availability", "fulltexthtml", "url"),
                        urlFrom(item, "fullTextUrlList.fullTextUrl", "documentStyle", "xml", "url"));
            candidate.fullTextUrl = open || !fullText.isEmpty() ? first(fullText, url(item, "fullTextUrlList.fullTextUrl.0.url")) : "";
            add(out, candidate, limit);
        }
        return out;
    }
    /** Full text xml lives beside the configured search endpoint, so fixtures stay on loopback. */
    private static String restBase() {
        String value = endpoint("europepmc");
        int cut = value.indexOf("/search");
        return cut > 0 ? value.substring(0, cut) : value;
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

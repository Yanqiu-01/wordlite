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
    }
    public static final class Limits {
        public int timeoutSeconds = 20;
        public int perEngine = 12;
        public String coreKey = "";
        /** host:port of an HTTP proxy for this retrieval pass, empty to dial out directly. */
        public String proxy = "";
    }
    static final int MAX_TEXT = 64 * 1024, MAX_AUTHORS = 6;
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
            ApiClient.Response binary = HttpTransport.postBytes(endpoint(name),
                    WanfangProtocol.request(phrase, 1, per), WanfangProtocol.CONTENT_TYPE, headers,
                    safe.timeoutSeconds, HttpTransport.MAX_BODY, cancellation, proxyFor(safe));
            return WanfangProtocol.parse(binary.raw, per);
        }
        /* 知网这个检索口只认 POST 表单，而且要看一眼浏览器样的请求头，回来的还是带高亮标签的 HTML，
           所以它不进下面那套 JSON 解析，整个交给 CnkiSearch。 */
        if (name.equals("cnki")) {
            ApiClient.Response page = HttpTransport.post(endpoint(name), CnkiSearch.form(phrase, 1),
                    CnkiSearch.headers(), safe.timeoutSeconds, HttpTransport.MAX_BODY, cancellation,
                    proxyFor(safe));
            try { return CnkiSearch.parse(page.body, per); }
            catch (RuntimeException error) { throw new IOException("知网检索响应无法解析"); }
        }
        ApiClient.Response response = name.equals("ncpssd")
                ? HttpTransport.post(endpoint(name), formFor(phrase, per), headers,
                        safe.timeoutSeconds, HttpTransport.MAX_BODY, cancellation, proxyFor(safe))
                : HttpTransport.get(endpoint(name) + "?" + queryFor(name, phrase, per),
                        headers, safe.timeoutSeconds, HttpTransport.MAX_BODY, cancellation, proxyFor(safe));
        Object root;
        try { root = name.equals("arxiv") || name.equals("cqvip") ? response.body : ApiJson.parse(response.body); }
        catch (RuntimeException error) { throw new IOException("检索响应格式无效"); }
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
        } catch (RuntimeException error) { throw new IOException("检索响应格式无效"); }
        return found;
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
     * 维普不给匿名程序留 API，检索结果是服务端渲染在 HTML 里的，题名由客户端脚本后填，服务端只给
     * 摘要、作者、刊名、年期和文献页地址。查重比对要的是可比对的正文，摘要够用；报告里这一行以
     * "《刊名》 年 期"署名并带上文献页地址，仍然能人工核到出处。
     */
    private static ArrayList<Candidate> parseCqvip(String html, int limit) {
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
            String venue = journal;
            if (venue.isEmpty()) {
                /* 学位论文没有期刊链接，署名退到培养单位，报告里至少看得出是谁的学校。 */
                String organ = Xml.stripTags(lastMatch(CQVIP_ORGAN, head)).replaceAll("^\\[[0-9]+]\\s*", "").trim();
                if (!organ.isEmpty()) venue = document.startsWith("degree") ? "学位论文 " + organ : organ;
            }
            candidate.source.title = venue.isEmpty() ? "维普记录 " + document
                    : venue + (year.isEmpty() ? "" : " " + year + "年") + (issue.isEmpty() ? "" : "第" + issue + "期");
            candidate.abstractText = clip(Xml.stripTags(html.substring(open + 1, close)));
            add(out, candidate, limit);
        }
        return out;
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

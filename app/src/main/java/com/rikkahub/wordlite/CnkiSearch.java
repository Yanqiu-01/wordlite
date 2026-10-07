package com.rikkahub.wordlite;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知网的匿名检索协议，走的是知网空间老门户 search.cnki.com.cn，不是 kns.cnki.net。
 *
 * 这条路是一段一段试出来的：kns8s 的检索接口对匿名会话一律 302 到滑块验证，kns 的 /sug 建议口
 * 被同一道验证挡着（403 blockPuzzle），手机知网的文献检索页要登录（302 到 account/login），
 * 网关上那个真正的文献检索接口 /m052/web/api/article/search 回得干脆——403 用户未登录。
 * 只剩知网空间这条老门户对匿名调用者开门。
 *
 * 它有一对孪生路径，别弄错：/api/search/listresult 那一个稳定 504（实测 50 秒超时），
 * 活下来的是 /search/listresult，回来的是一截 HTML 片段而不是 JSON，得按标签读。
 * 每条记录在片段里出现两次——div 版带摘要预览，table 版带出处与发表日期，而期刊那条
 * 恰好把日期和刊名只写在 table 版里，所以两边都要读，按 data-fn 文献号对齐并去重。
 * 摘要只有 128 字预览，那是公开页面的上限，不是取到了没用。国内出口与海外出口结果一致。
 */
final class CnkiSearch {
    static final String SEARCH = "https://search.cnki.com.cn/search/listresult";
    /** 服务端不校验来源，但缺了 Referer 就不像正常检索页发出去的请求。 */
    static final String REFERER = "https://search.cnki.com.cn/Search/Result";
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    /** 一页 20 条，服务端只认到第 50 页，再往后是空的。 */
    static final int MAX_PAGE = 50;
    /** 条目页在手机上按库分目录，会议、学位论文与期刊不在同一个前缀下。 */
    static final String TOUCH = "https://wap.cnki.net/touch/web/";

    /** 一条记录一个 div，切块比配对嵌套标签省事，也和服务端的输出顺序一致。 */
    private static final String ITEM = "<div class=\"list-item\">";
    private static final String TITLE_SUFFIX = "CNKI文献";
    /* 学位论文的文献号带点（1026316469.nh），别按纯字母数字去卡，否则整条记录会被无声丢掉。 */
    private static final Pattern CODE = Pattern.compile("data-fn=\"([A-Za-z0-9][A-Za-z0-9.\\-_]{4,})\"");
    private static final Pattern TITLE_ROW = Pattern.compile("(?s)<p class=\"tit[^\"]*\">(.*?)</p>");
    private static final Pattern TITLE_LINK =
            Pattern.compile("(?is)<a\\b[^>]*\\btitle=\"([^\"]*)\"[^>]*>(.*?)</a>");
    private static final Pattern ABSTRACT = Pattern.compile("(?s)<p class=\"nr\">(.*?)</p>");
    private static final Pattern SOURCE = Pattern.compile("(?s)<p class=\"source\">(.*?)</p>");
    /* 作者链接的 href 一定带 author=，关键词链接也是 data-key，靠这个把它们分开。 */
    private static final Pattern AUTHOR =
            Pattern.compile("(?is)<a\\b[^>]*\\bdata-key=\"([^\"]*)\"[^>]*href=\"[^\"]*[?&]author=[^\"]*\"");
    private static final Pattern VENUE = Pattern.compile("(?s)<span title=\"([^\"]{2,})\"");
    private static final Pattern LABEL =
            Pattern.compile("(?s)<span>\\s*([^<>]*[\\u4e00-\\u9fa5][^<>]*)\\s*</span>");
    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    /** 详情链接里的库前缀就是数据库本身：CJFDTOTAL- 是期刊，CPFDTOTAL- 是会议。 */
    private static final Pattern DATABASE = Pattern.compile("(?i)/([A-Za-z]{2,10})TOTAL-");
    /** 学位论文的链接没有 TOTAL- 这一层，只能看域名：cdmd.cnki.com.cn。 */
    private static final Pattern HOST = Pattern.compile("(?i)//([a-z0-9\\-]+)\\.cnki\\.com\\.cn/Article/");
    private static final Pattern ROW = Pattern.compile("(?s)<tr\\b[^>]*>(.*?)</tr>");
    private static final Pattern VENUE_LINK =
            Pattern.compile("(?is)<a\\b[^>]*class=\"source-i\"[^>]*>(.*?)</a>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern CJK_SPACE =
            Pattern.compile("(?<=[\\u4e00-\\u9fa5])[ \\t]+(?=[\\u4e00-\\u9fa5])");

    private CnkiSearch() { }

    /**
     * 检索表单。中文一律百分号编码，这一步不能省：命令行和部分 HTTP 客户端会把裸中文按本地码发出去，
     * 服务端回一段四个问号的错误，看着像被拒绝，其实只是编码坏了。页码夹在 1 到 50 之间。
     */
    static String form(String phrase, int page) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("Content=").append(encode(phrase == null ? "" : phrase.trim()));
        out.append("&Type=0&Order=2&Page=").append(page < 1 ? 1 : page > MAX_PAGE ? MAX_PAGE : page);
        out.append("&Match=0&IntervalTime=0&ArticleType=0");
        return out.toString();
    }

    /** 缺 X-Requested-With 时服务端把它当普通页面直连，给的是空壳而不是结果。 */
    static LinkedHashMap<String, String> headers() {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        out.put("User-Agent", USER_AGENT);
        out.put("Referer", REFERER);
        out.put("X-Requested-With", "XMLHttpRequest");
        return out;
    }

    /**
     * 结果片段转候选文献。脏页面、半截页面一律给空表而不是抛异常：一个源挂了不该把整轮查重带倒，
     * 而调用方本来就把空表当成这一源没结果。
     */
    static ArrayList<PaperSources.Candidate> parse(String html, int limit) {
        ArrayList<PaperSources.Candidate> out = new ArrayList<PaperSources.Candidate>();
        if (html == null || html.isEmpty() || limit <= 0) return out;
        LinkedHashMap<String, String[]> index = index(html);
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        int table = html.indexOf("<table");
        int at = 0;
        while (out.size() < limit) {
            int start = html.indexOf(ITEM, at);
            if (start < 0) break;
            int next = html.indexOf(ITEM, start + ITEM.length());
            int end = next < 0 ? (table > start ? table : html.length()) : next;
            at = next < 0 ? html.length() : next;
            PaperSources.Candidate candidate = record(html.substring(start, end), index);
            if (candidate == null || candidate.source.id.isEmpty() || !seen.add(candidate.source.id)) continue;
            PaperSources.add(out, candidate, limit);
        }
        return out;
    }

    /**
     * 条目页按库分目录，路由错了就是 404：会议论文的号放进 Journal 会 302 到 code=KbaseNo，
     * 学位论文的号（带点那种）放进 Journal 直接 code=RegexErr。认不出来的库按期刊走，
     * 那是公开页面上绝大多数文献的正确路由。
     */
    static String articleUrl(String code, String database) {
        String name = database == null ? "" : database.trim().toUpperCase(Locale.ROOT);
        String folder = name.endsWith("CPFD") ? "Conference/Article/"
                : name.endsWith("CDMD") || name.endsWith("CMFD") ? "Dissertation/Article/"
                : "Journal/Article/";
        return TOUCH + folder + code + ".html";
    }

    /**
     * 一次遍历表格版，按文献号记下出处、发表年与类型。div 版缺哪一项就从这里补哪一项：
     * 期刊那几条的日期和刊名只在 table 版里出现。值固定三位：0 出处，1 年份，2 类型。
     */
    private static LinkedHashMap<String, String[]> index(String html) {
        LinkedHashMap<String, String[]> index = new LinkedHashMap<String, String[]>();
        Matcher row = ROW.matcher(html);
        while (row.find()) {
            String body = row.group(1);
            String code = first(CODE, body);
            if (code.isEmpty() || index.containsKey(code)) continue;
            index.put(code, new String[] { plain(first(VENUE_LINK, body)), yearOf(body), last(body, LABEL) });
        }
        return index;
    }

    private static PaperSources.Candidate record(String block, LinkedHashMap<String, String[]> index) {
        String database = first(DATABASE, block);
        if (database.isEmpty()) database = first(HOST, block);
        String code = first(CODE, block);
        if (code.isEmpty()) return null;
        String title = title(block);
        if (title.isEmpty()) return null;

        String source = first(SOURCE, block);
        String[] known = index.get(code);
        /* 出处取最后一个带 title 的 span：学位论文那条在前面还有一个导师的 span。 */
        String venue = last(source, VENUE);
        if (venue.isEmpty() && known != null) venue = plain(known[0]);
        String year = yearOf(source);
        if (year.isEmpty() && known != null) year = known[1];
        String label = last(source, LABEL);
        if (label.isEmpty() && known != null) label = known[2];
        ArrayList<String> names = authors(source);

        PaperSources.Candidate candidate = new PaperSources.Candidate();
        candidate.source.engine = "cnki";
        candidate.source.id = code;
        candidate.source.locator = articleUrl(code, database);
        candidate.source.title = title;
        candidate.source.authors = PaperSources.join(names);
        candidate.source.year = year;
        candidate.abstractText = PaperSources.clip(abstractOf(block, title, names, venue, year, label));
        return candidate;
    }

    /**
     * 题名优先用链接里的文字而不是 title 属性：属性里没有高亮标签，走属性等于绕开真正要处理的坑。
     * 命中的词被包在 span 里，前后本来是一个连续的中文词，去标签时一个空格都不能补。
     */
    private static String title(String block) {
        String head = first(TITLE_ROW, block);
        String attribute = "";
        String inner = "";
        Matcher link = TITLE_LINK.matcher(head);
        if (link.find()) {
            attribute = link.group(1);
            inner = link.group(2);
        }
        String title = plain(inner);
        if (title.endsWith(TITLE_SUFFIX)) title = title.substring(0, title.length() - TITLE_SUFFIX.length());
        title = tighten(title.trim());
        return title.isEmpty() ? tighten(plain(attribute)) : title;
    }

    private static ArrayList<String> authors(String source) {
        ArrayList<String> out = new ArrayList<String>();
        Matcher m = AUTHOR.matcher(source);
        while (m.find() && out.size() < PaperSources.MAX_AUTHORS) {
            String name = plain(m.group(1));
            if (name.isEmpty() || out.contains(name)) continue;
            out.add(name);
        }
        return out;
    }

    /**
     * 摘要只有 128 字预览，够不上一个稳定命中，所以把题录接在后面一起交给比对。
     * 中间空一行，是因为比对按句子切，摘要的最后一句和作者名粘在一起会两边都失真。
     */
    private static String abstractOf(String block, String title, ArrayList<String> names,
                                    String venue, String year, String label) {
        StringBuilder out = new StringBuilder(plain(first(ABSTRACT, block)));
        StringBuilder tail = new StringBuilder();
        append(tail, title);
        append(tail, PaperSources.join(names));
        append(tail, venue);
        append(tail, year);
        append(tail, label);
        if (out.length() > 0 && tail.length() > 0) out.append('\n');
        return out.append(tail).toString();
    }

    private static void append(StringBuilder out, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (out.length() > 0) out.append(' ');
        out.append(value.trim());
    }

    private static String yearOf(String text) {
        Matcher m = DATE.matcher(text);
        if (m.find()) return m.group(1);
        return PaperSources.year(text);
    }

    /**
     * 去标签但不补空格。PaperSources.Xml.stripTags 会在标签断开处补一个空格，那对段落排版是对的，
     * 对中文题名却是伤害：补出来的空格把"深度学习"劈成两半，比对时相似度被白白稀释。
     * 段落级的空白仍然收成一个空格，只把汉字之间的空格删干净。
     */
    private static String plain(String raw) {
        if (raw == null) return "";
        String text = raw.replace("<!--[-->", "").replace("<!--]-->", "");
        text = text.replace('\u00a0', ' ').replace('　', ' ');
        text = TAG.matcher(text).replaceAll("");
        return tighten(PaperSources.Xml.unescape(text).replaceAll("\\s+", " ").trim());
    }

    /** 只删汉字之间的空格：拉丁词之间的空格是真空格，得留着。 */
    private static String tighten(String value) {
        String out = value;
        while (true) {
            String next = CJK_SPACE.matcher(out).replaceAll("");
            if (next.equals(out)) return out;
            out = next;
        }
    }

    private static String first(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /** 一个块里可能有好几个这样的 span（日期、类型、导师、机构），最后一个才是要点。 */
    private static String last(String text, Pattern pattern) {
        String out = "";
        Matcher m = pattern.matcher(text);
        while (m.find()) out = plain(m.group(1));
        return out;
    }

    private static String encode(String value) throws IOException {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException error) {
            throw new IOException("检索短语无法编码", error);
        }
    }
}
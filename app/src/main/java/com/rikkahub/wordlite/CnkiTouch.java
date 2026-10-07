package com.rikkahub.wordlite;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 手机知网的公开条目页（wap.cnki.net/touch/web/…）。检索在 CnkiSearch 那一头（知网空间的老门户），
 * 这一路只管另一件事：手上已经有一条知网链接或文献号，怎么把它变成自建库里的一篇。
 * kns 的检索口对匿名会话 302 到滑块验证，条目页与刊期目录却不需要登录也不撞验证码，
 * 于是按文献号取一条题录、按刊期号看当期目录，这两件是白拿的；会议与学位论文另有目录前缀，
 * 编号塞错目录只会拿到 302。摘要那一格服务端只给 128 字预览，这是公开页面的上限，不是取到了没用。
 */
final class CnkiTouch {
    static final String BASE = "https://wap.cnki.net/touch/web/Journal/";
    /** 一期几十到上百篇，一次最多导这么多，免得手一抖把整刊拖进手机。 */
    static final int MAX_IMPORT = 40;

    private static final Pattern CODE = Pattern.compile("([A-Z]{2,8})(\\d{6,})");
    private static final Pattern ARTICLE_IN_TOC =
            Pattern.compile("/Journal/Article/([A-Z]{2,8}\\d{6,})\\.html");
    private static final Pattern TITLE_TAG = Pattern.compile("(?s)<title>(.*?)</title>");
    private static final Pattern TITLE_HEAD = Pattern.compile("(?s)<div class=\"c-card__title2\">(.*?)</div>");
    private static final Pattern ABSTRACT = Pattern.compile("(?s)<div class=\"c-card__aritcle\">(.*?)</div>");
    private static final Pattern AUTHOR_BLOCK = Pattern.compile("(?s)<div class=\"c-card__author\">(.*?)</div>");
    private static final Pattern AUTHOR = Pattern.compile("(?s)Scholar/Index/\\?code=[^\"]*\">(.*?)</a>");
    private static final Pattern ORGANIZATION = Pattern.compile("(?s)Organization/List/[^\"]*\">(.*?)</a>");
    private static final Pattern KEYWORDS = Pattern.compile("(?is)<meta[^>]+name=\"keywords\"[^>]+content=\"([^\"]*)\"");

    private CnkiTouch() { }

    /** 是链接还是裸文献号都吃：认得出期刊码加年期号就算数，认不出直说。 */
    static String code(String input) {
        String raw = input == null ? "" : input.trim().toUpperCase(java.util.Locale.ROOT);
        if (raw.isEmpty()) return "";
        Matcher m = CODE.matcher(raw.replace("HTTPS:", "").replace("HTTP:", ""));
        while (m.find()) {
            /* 域名里也可能有两三个字母串，真的期刊号后面必然跟着六位以上的数字。 */
            if (m.group(2).length() >= 6) return m.group(1) + m.group(2);
        }
        return "";
    }

    /** 六位是刊期（JSYY202001），再多就是某一篇文章（JSYY202001008）。 */
    static boolean isIssue(String code) {
        int digits = code == null ? 0 : code.length() - letters(code);
        return digits == 6;
    }

    static String articleUrl(String code) { return BASE + "Article/" + code + ".html"; }

    static String issueUrl(String code) { return BASE + "List/" + code + ".html"; }

    /** 会议论文的编号走 Conference 那一路：期刊页拿它只会 302。同一个编号不会两个库都在，所以取不到就换这条。 */
    static String conferenceUrl(String code) {
        return "https://wap.cnki.net/touch/web/Conference/Article/" + code + ".html";
    }

    /** 目录页里的文献号，按页面顺序，重复的只留一次。 */
    static ArrayList<String> issueCodes(String html, int limit) {
        LinkedHashSet<String> found = new LinkedHashSet<String>();
        if (html == null) return new ArrayList<String>();
        Matcher m = ARTICLE_IN_TOC.matcher(html);
        while (m.find() && found.size() < Math.max(1, Math.min(limit, MAX_IMPORT))) found.add(m.group(1));
        return new ArrayList<String>(found);
    }

    /**
     * 条目页 → 一段能直接进自建库的纯文本。字段之间空行隔开，是因为自建库按句子切分，
     * 把题录挤在一行里会让"题名+作者"变成一句，比对时长句的相似度会被题录噪声拉低。
     */
    static String record(String html) {
        String page = html == null ? "" : html;
        StringBuilder out = new StringBuilder();
        String title = title(page);
        String journal = "";
        String issue = "";
        String tag = first(TITLE_TAG, page);
        /* <title> 形如"题名-机械设计与研究2020年01期-手机知网"，刊名与年期只有这里给得全。 */
        Matcher tail = Pattern.compile("-(.*?)(\\d{4})年(\\d{1,2})期-手机知网$").matcher(tag);
        if (tail.find()) {
            journal = clean(tail.group(1));
            issue = tail.group(2) + "年第" + tail.group(3) + "期";
            if (title.isEmpty()) title = clean(tail.group(0).substring(1, tail.group(0).length() - 1
                    - tail.group(2).length() - 4 - tail.group(3).length()));
        }
        append(out, "题名", title);
        append(out, "作者", joinNames(AUTHOR, firstBlock(AUTHOR_BLOCK, page)));
        append(out, "机构", firstBlock(ORGANIZATION, page).replace("；", "; ").replace(";", "; "));
        append(out, "来源", (journal + " " + issue).trim());
        String keywords = clean(first(KEYWORDS, page));
        if (!keywords.isEmpty() && !keywords.equals(title)) {
            ArrayList<String> kept = new ArrayList<String>();
            for (String word : keywords.split("[,，]")) {
                String one = clean(word);
                if (one.isEmpty() || one.equals(title) || one.contains("下载")) continue;
                kept.add(one);
            }
            append(out, "关键词", join(kept));
        }
        append(out, "摘要", clean(first(ABSTRACT, page)));
        return out.toString().trim();
    }

    static String title(String html) {
        String page = html == null ? "" : html;
        String head = clean(first(TITLE_HEAD, page));
        return !head.isEmpty() ? head : clean(first(TITLE_TAG, page).split("-手机知网")[0]);
    }

    /** 取一次页面。知网这条路上没有查询串可拼，编号已经过 code() 的形状校验。 */
    static String get(String url, int timeoutSeconds, java.net.Proxy proxy,
                      ApiClient.Cancellation cancellation) throws IOException {
        ApiClient.Response response = HttpTransport.get(url, null, timeoutSeconds,
                HttpTransport.MAX_BODY, cancellation, proxy);
        return response.body == null ? "" : response.body;
    }

    private static void append(StringBuilder out, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (out.length() > 0) out.append("\n\n");
        out.append(label).append("：").append(value.trim());
    }

    private static String joinNames(Pattern pattern, String block) {
        ArrayList<String> names = new ArrayList<String>();
        Matcher m = pattern.matcher(block);
        while (m.find() && names.size() < PaperSources.MAX_AUTHORS) {
            String name = clean(m.group(1));
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }
        return join(names);
    }

    private static String firstBlock(Pattern pattern, String page) {
        Matcher m = pattern.matcher(page);
        return m.find() ? m.group(1) : "";
    }

    private static String first(Pattern pattern, String page) {
        Matcher m = pattern.matcher(page);
        return m.find() ? m.group(1) : "";
    }

    private static String join(ArrayList<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(", ");
            out.append(value);
        }
        return out.toString();
    }

    private static int letters(String code) {
        int at = 0;
        while (at < code.length() && Character.isLetter(code.charAt(at))) at++;
        return at;
    }

    /** 去标签、去注释占位，再把空白收干净：Vue 的服务端渲染在文本里塞满了 <!---->。 */
    private static String clean(String raw) {
        if (raw == null) return "";
        String text = raw.replace("<!--[-->", "").replace("<!--]-->", "").replace("<!--", "").replace("-->", "");
        text = text.replace('\u00A0', ' ').replace("　", " ");   // 服务端在作者名后面塞不间断空格
        return PaperSources.Xml.stripTags(text).replace("　", " ").trim();
    }
}

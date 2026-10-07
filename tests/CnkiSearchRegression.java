package com.rikkahub.wordlite;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * CnkiSearch 回归：表单编码与页码夹取、请求头、data-fn 去重、会议/期刊/学位论文三种条目页路由、
 * 高亮标签去掉之后不能在中文里留空格，以及脏页面只给空表不许抛异常。
 * 全程只读 tests/samples/cnki-search.html，不发网络请求。
 */
public final class CnkiSearchRegression {
    private static int count;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** 夹具里八条记录的真实顺序：前四条是会议（CPFDTOTAL），后四条是期刊（CJFDTOTAL）。 */
    private static final String[] IDS = {
            "ZGQX202610027047", "ZGQX202610027043", "ZGQX202610027046", "ZGQX202610027060",
            "JXGL202628011", "XBDI202605014", "DQXX202610022", "HBRW202610021", };
    private static final String CONFERENCE = "https://wap.cnki.net/touch/web/Conference/Article/";
    private static final String JOURNAL = "https://wap.cnki.net/touch/web/Journal/Article/";
    private static final String DISSERTATION = "https://wap.cnki.net/touch/web/Dissertation/Article/";

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    public static void main(String[] args) {
        try {
            String path = args.length > 0 ? args[0] : "tests/samples/cnki-search.html";
            String html = read(path);
            check(html.contains("hidTotalCount"), "夹具保留了服务端给的结果总数");
            form();
            narrowing();
            headers();
            routing();
            parsed(html);
            highlights(html);
            dedupe(html);
            dissertation();
            garbage();
        } catch (Throwable error) {
            System.out.println("FAIL " + error);
            error.printStackTrace(System.out);
            System.exit(1);
        }
        System.out.println("SUMMARY " + count + " assertions passed (live endpoint not contacted here).");
    }

    private static void form() throws Exception {
        String expected = "Content=%E6%B7%B1%E5%BA%A6%E5%AD%A6%E4%B9%A0+%E5%9B%BE%E5%83%8F%E5%88%86%E5%89%B2"
                + "&Type=0&Order=2&Page=1&Match=0&IntervalTime=0&ArticleType=0";
        String body = CnkiSearch.form("深度学习 图像分割 综述", 1);
        check(body.equals(expected), "form 按 UTF-8 百分号编码中文并带全六个固定参数，第三个词被收窄掉");
        check(ascii(body), "表单里没有裸中文，服务端收到的是纯 ASCII");
        check(CnkiSearch.form("深度学习", 0).contains("&Page=1&"), "页码 0 被夹到第一页");
        check(CnkiSearch.form("深度学习", -9).contains("&Page=1&"), "负页码被夹到第一页");
        check(CnkiSearch.form("深度学习", 999).contains("&Page=" + CnkiSearch.MAX_PAGE + "&"),
                "超出上限的页码被夹到 " + CnkiSearch.MAX_PAGE);
        check(CnkiSearch.form(null, 1).startsWith("Content=&"), "空短语退化成空 Content 而不是抛异常");
        check(CnkiSearch.SEARCH.equals("https://search.cnki.com.cn/search/listresult"),
                "检索地址是活的那个，不是五十秒后 504 的 /api/ 版本");
    }

    /** 查询词收窄：多词查询在知网那个老口子上会换回一批固定填充条目，只留最具体的两个词。 */
    private static void narrowing() throws Exception {
        check("宽禁带半导体 互连材料".equals(CnkiSearch.narrow("宽禁带半导体 封装 互连材料 可靠性")),
                "四个词里留最长的两个，实测只有这一档回来的是对题结果");
        check("封装 可靠性".equals(CnkiSearch.narrow("封装 可靠性")), "两个词原样发出，哪怕都很泛——收窄只数词数不猜语义");
        check("宽禁带半导体".equals(CnkiSearch.narrow("宽禁带半导体")), "单词查询不动");
        check("".equals(CnkiSearch.narrow("")) && "".equals(CnkiSearch.narrow("   ")),
                "空与全空白都退化成空串而不是抛异常");
        check("".equals(CnkiSearch.narrow(null)), "null 退化成空串");
        check("深度学习 图像分割".equals(CnkiSearch.narrow("深度学习 图像分割 综述")),
                "三词砍到两个，短的那个先掉");
        check("AI 研究".equals(CnkiSearch.narrow("AI 研究 与 展望")), "单字词不参与，剩下的仍然只留两个");
        check(CnkiSearch.form("宽禁带半导体 封装 互连材料 可靠性", 3).contains("Content="
                + java.net.URLEncoder.encode("宽禁带半导体 互连材料", "UTF-8") + "&Type=0"),
                "form 发出去的就是收窄后的两个词");
    }

    private static void headers() {
        LinkedHashMap<String, String> headers = CnkiSearch.headers();
        check("XMLHttpRequest".equals(headers.get("X-Requested-With")),
                "带上 X-Requested-With，否则服务端按页面直连给空壳");
        check("https://search.cnki.com.cn/Search/Result".equals(headers.get("Referer")), "Referer 指向检索页");
        String agent = headers.get("User-Agent");
        check(agent != null && agent.startsWith("Mozilla/5.0") && agent.contains("Chrome/"), "用浏览器 User-Agent");
    }

    private static void routing() {
        check(CnkiSearch.articleUrl("ZGQX202610027047", "CPFD").equals(CONFERENCE + "ZGQX202610027047.html"),
                "会议库的文献号路由到 Conference 目录");
        check(CnkiSearch.articleUrl("JXGL202628011", "CJFD").equals(JOURNAL + "JXGL202628011.html"),
                "期刊库的文献号路由到 Journal 目录");
        check(CnkiSearch.articleUrl("JXGL202628011", "www").equals(JOURNAL + "JXGL202628011.html")
                        && CnkiSearch.articleUrl("JXGL202628011", "").equals(JOURNAL + "JXGL202628011.html"),
                "认不出来的库默认走 Journal 目录");
        check(CnkiSearch.articleUrl("1026316469.nh", "CDMD").equals(DISSERTATION + "1026316469.nh.html"),
                "学位论文库路由到 Dissertation 目录（塞进 Journal 会被服务端判 RegexErr）");
    }

    private static void parsed(String html) {
        ArrayList<PaperSources.Candidate> out = CnkiSearch.parse(html, 12);
        check(out.size() == IDS.length, "解析出 " + IDS.length + " 条候选");
        for (int i = 0; i < out.size(); i++) {
            check(out.get(i).source.id.equals(IDS[i]), "第 " + (i + 1) + " 条的 data-fn 文献号原样取出 " + IDS[i]);
            check("cnki".equals(out.get(i).source.engine), "第 " + (i + 1) + " 条标注来源引擎为 cnki");
            check(out.get(i).source.locator.endsWith(IDS[i] + ".html"), "第 " + (i + 1) + " 条条目页地址带自己的文献号");
            String year = out.get(i).source.year;
            check(year.length() == 4 && Integer.parseInt(year) >= 1990 && Integer.parseInt(year) <= 2100,
                    "第 " + (i + 1) + " 条年份是完整四位数字 " + year);
        }
        boolean conference = true;
        for (int i = 0; i < 4; i++) {
            conference &= out.get(i).source.locator.startsWith(CONFERENCE);
        }
        check(conference, "前四条会议文献全部路由到 Conference 目录");
        boolean journal = true;
        for (int i = 4; i < out.size(); i++) {
            journal &= out.get(i).source.locator.startsWith(JOURNAL);
        }
        check(journal, "后四条期刊文献全部路由到 Journal 目录");

        PaperSources.Candidate first = out.get(0);
        check(first.source.authors.equals("王清龙, 向立莉, 成勤, 姚曼, 王海, 乔木"),
                "作者按页面顺序取到并截在 " + PaperSources.MAX_AUTHORS + " 人");
        check(first.source.year.equals("2026"), "会议记录的年份取自 div 版里的发表时间");
        check(out.get(4).source.year.equals("2026"), "期刊记录的年份从 table 版补齐（div 版不给日期）");
        check(out.get(4).abstractText.contains("教学与管理"), "期刊记录的出处进了题录");
        check(first.abstractText.indexOf('\n') > 0 && first.abstractText.contains("地面气象观测")
                && first.abstractText.contains("中国会议"), "摘要预览与题录拼在一起交给比对");
    }

    private static void highlights(String html) {
        ArrayList<PaperSources.Candidate> out = CnkiSearch.parse(html, 12);
        String title = out.get(0).source.title;
        check(title.equals("基于AOV边缘摄像头与轻量化深度学习的智能全天空云观测系统"),
                "带高亮的题名去标签之后与原题名一致");
        check(title.contains("轻量化深度学习的"), "被 span 劈开的中文词重新连成一个词");
        check(out.get(5).source.title.endsWith("特征学习"), "行尾的高亮去掉之后没有留下断口");
        check(out.get(6).source.title.contains("语义分割与时序分析"), "词中间的高亮没有插进空格");
        for (int i = 0; i < out.size(); i++) {
            String text = out.get(i).source.title + out.get(i).source.authors + out.get(i).abstractText;
            check(!text.contains("<") && !text.contains("span") && !text.contains("color:red")
                            && !text.contains("style=") && !text.contains("&nbsp"),
                    "第 " + (i + 1) + " 条不留高亮标签与转义残留");
            check(!out.get(i).source.title.matches(".*[\\u4e00-\\u9fa5] [\\u4e00-\\u9fa5].*"),
                    "第 " + (i + 1) + " 条题名里没有塞进中文之间的空格");
            check(!out.get(i).source.title.endsWith("CNKI文献"), "第 " + (i + 1) + " 条题名去掉了结果类型尾巴");
        }
    }

    private static void dedupe(String html) {
        int occurrences = html.split("data-fn=", -1).length - 1;
        check(occurrences == IDS.length * 2, "夹具里每条记录确实出现两次（div 版与 table 版）");
        check(CnkiSearch.parse(html, 12).size() == IDS.length, "按 data-fn 去重之后不重复计条目");
        check(CnkiSearch.parse(html, 3).size() == 3, "limit 被尊重");
        check(CnkiSearch.parse(html, 0).isEmpty() && CnkiSearch.parse(html, -1).isEmpty(),
                "limit 为零或负数时给空表");
        check(CnkiSearch.parse(html, 200).size() == IDS.length, "limit 大于记录数时不会凭空多出条目");
    }

    /** 学位论文那条的文献号带小数点，形状和期刊完全不同，专门盯住它。 */
    private static void dissertation() {
        String block = "<div class=\"list-item\"><p class=\"tit clearfix\">"
                + "<a href=\"//cdmd.cnki.com.cn/Article/CDMD-11079-1026316469.htm\""
                + " title=\"奢侈品品牌形象的媒介化呈现对消费者购买意愿的影响研究\">"
                + "奢侈品品牌形象的媒介化呈现对消费者购买意愿的影响研究&nbsp;&nbsp;CNKI文献</a></p>"
                + "<p class=\"nr\">绪论部分说明研究背景与意义。</p><p class=\"source\">"
                + "<span title=\"\"><a data-key=\"肖玉琳\" href=\"https://search.cnki.com.cn/Search/"
                + "Result?author=%E8%82%96%E7%8E%89%E7%90%B3\">肖玉琳</a></span>"
                + "<span title=\"刘茜\">导师: <a>刘茜</a></span><span title=\"成都大学\"> 成都大学 </span>"
                + "<span>2026-12-01</span><span>学位论文</span></p>"
                + "<div class=\"checkbox1\" data-fn=\"1026316469.nh\" style=\"display:none;\"></div></div>";
        ArrayList<PaperSources.Candidate> out = CnkiSearch.parse(block, 5);
        check(out.size() == 1, "带小数点的学位论文号不再被无声丢掉");
        check(out.get(0).source.id.equals("1026316469.nh"), "学位论文号原样保留小数点");
        check(out.get(0).source.locator.equals(DISSERTATION + "1026316469.nh.html"), "学位论文条目页走 Dissertation 目录");
        check(out.get(0).source.authors.equals("肖玉琳"), "导师不算进作者里");
        check(out.get(0).abstractText.contains("成都大学") && !out.get(0).abstractText.contains("刘茜"),
                "题录里的出处取学校而不是导师");
    }

    private static void garbage() {
        check(CnkiSearch.parse(null, 12).isEmpty(), "null 页面给空表");
        check(CnkiSearch.parse("", 12).isEmpty(), "空页面给空表");
        check(CnkiSearch.parse("抱歉，没有找到相关内容", 12).isEmpty(), "无结果提示页给空表");
        check(CnkiSearch.parse("<div class=\"lplist\"><div class=\"list-item\"><p class=\"tit", 12).isEmpty(),
                "半截记录不抛异常");
        check(CnkiSearch.parse("<table><tr><td>2026-09-30</td></tr></table>", 12).isEmpty(),
                "只有表格没有记录也给空表");
        check(CnkiSearch.parse("<div class=\"list-item\"><span data-fn=\"ABCD2026010101\"></span>"
                + "</div>", 12).isEmpty(), "有文献号没题名的记录被跳过");
        check(CnkiSearch.parse("<div class=\"list-item\">文献号 <span data-fn=\"ZZZZ2026010101\"></span>"
                + "<p class=\"tit\"><a title=\"一个题名\" href=\"//www.cnki.com.cn/Article/CJFDTOTAL-"
                + "ZZZZ2026010101.htm\">一个题名</a></p></div>", 12).size() == 1, "缺摘要时仍按题录出一条");
    }

    private static boolean ascii(String value) {
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) > 127) return false;
        return true;
    }

    private static String read(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), UTF8);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("missing fixture " + path, error);
        }
    }
}

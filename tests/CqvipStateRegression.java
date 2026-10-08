package com.rikkahub.wordlite;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * 维普检索页题录解析回归。夹具是一段照 Nuxt 输出手写的 window.__NUXT__：值全堆在末尾实参里，函数体内
 * 只剩单字母变量名——这正是维普这条路唯一的麻烦（服务端把 <a class="title"> 印成空标签，真题名只在这份
 * 状态里），所以必须连着"变量名 → 字面量"这一层一起验，只验 HTML 扫描等于没验。
 *

 * 夹具两份都在仓库里：{@code tests/samples/cqvip-search.html} 是没有那份状态的老形状（服务端改了结构
 * 时的样子，一个字节都不许多报），{@code tests/samples/cqvip-search-titled.html} 是 2026-10-08 从
 * {@code www.cqvip.com/search} 原样取回的真实响应，题名的空标签与 {@code window.__NUXT__} 都还在里面。
 * 真实页找不到时打印 SKIP，不影响闸门。
 */
public final class CqvipStateRegression {
    private static int passed;

    /** 两条记录：一条期刊（journalInfo 给刊名），一条学位论文（只有 organInfo）。 */
    private static final String STATE = "<script>window.__NUXT__=(function(a,b,c,d,e,f,g,h,i,j,k){"
            + "return {layout:\"default\",data:[{listData:{count:2,records:["
            + "{id:a,doi:\"10.1\\u002Fj.issn.1001\",title:c,journalInfo:{id:d,name:e,num:f},"
            + "keywordInfo:[{id:d,name:g},{id:e,name:g},{id:f,name:\"半监督学习\"}],"
            + "objectInfo:{title:\"内层不该被取到的题名\"},authorInfo:[{id:d,name:k}],abstr:\"真摘要一句\",year:b},"
            + "{id:h,title:i,organInfo:[{id:d,name:j}],keywordInfo:[],abstr:\"学位论文摘要\",year:b}"
            + "]}}]};}(\"7105328027\",2021,\"基于A\\u002FB的方法\",\"1\",\"计算机应用\",\"8\","
            + "\"深度学习\",\"00075JL1MLD\",\"学位论文真题名\",\"东北大学\",\"张三\"));</script>";

    private static final String PAGE = "<html><body>"
            + "<a href=\"/doc/journal/7105328027?sign=abc&amp;expireTime=1&amp;type=1\" class=\"title font-size18\"></a>"
            + "<a href=\"/journal/1001\">计算机应用</a><a class=\"author-name\">张三</a> 2021年 第8期"
            + "<span class=\"abstr\">这是<em>真摘要</em>的一段，长度够入库比对。</span>"
            + "<a href=\"/doc/degree/00075JL1MLD?sign=def&amp;type=2\" class=\"title\"></a>"
            + "<a href=\"/organization/9\">[1] 东北大学</a> 2020年 第3期"
            + "<span class=\"abstr\">学位论文的摘要，同样够长够入库。</span>"
            + STATE + "</body></html>";

    /** 同一份 HTML 但没有那份状态：服务端改了结构时的老形状必须一个字都不许多。 */
    private static final String PAGE_NO_STATE = PAGE.replace("window.__NUXT__=", "window.__X__=");

    public static void main(String[] args) throws Exception {
        LinkedHashMap<String, CqvipState.Record> state = CqvipState.parse(PAGE);
        check(state.size() == 2, "状态里解出两条记录");
        CqvipState.Record paper = CqvipState.find(state, "journal/7105328027");
        check(paper != null, "HTML 的 journal/<号> 与状态里的 <号> 对得上行");
        check(paper != null && paper.title.equals("基于A/B的方法"), "真题名从状态里取到，转义的斜杠还原成 /");
        check(paper != null && paper.venue.equals("计算机应用"), "出处取 journalInfo.name，不是作者的机构");
        check(paper != null && paper.keywords.equals("深度学习; 半监督学习"),
                "关键词按顺序取到，重复的那一个只留一次");
        CqvipState.Record thesis = CqvipState.find(state, "degree/00075JL1MLD");
        check(thesis != null && thesis.title.equals("学位论文真题名"), "学位论文的题名同样取到");
        check(thesis != null && thesis.venue.equals("东北大学"),
                "没有 journalInfo 时出处退到 organInfo 第一条");
        check(CqvipState.find(state, "journal/999999") == null, "状态里没有的文献号交 null，不硬配");
        check(CqvipState.parse(null).isEmpty() && CqvipState.parse("<html></html>").isEmpty(),
                "null 与空壳页面交空表而不是崩");
        check(CqvipState.parse(PAGE.replace("records:[", "records:[{id:")).isEmpty(),
                "括号配不平的坏状态交空表，题名交回刊名兜底");
        check(CqvipState.find(null, "journal/1") == null, "空状态问不出记录");

        ArrayList<PaperSources.Candidate> rows = PaperSources.parseCqvip(PAGE, 10);
        check(rows.size() == 2, "两条记录都进了候选");
        check(rows.get(0).source.title.equals("基于A/B的方法《计算机应用》"),
                "署名 = 真题名《出处》，与万方那一路同一个写法，题名指纹才跨得过去");
        check(!rows.get(0).source.title.contains("内层不该被取到的题名"), "内层同名的 title 不许盖掉顶层题名");
        check(rows.get(1).source.title.equals("学位论文真题名《东北大学》"),
                "学位论文把培养单位放进《》，不再顶一个\"学位论文 \"前缀");
        check(rows.get(0).source.id.equals("journal/7105328027")
                        && rows.get(0).source.locator.equals("https://www.cqvip.com/doc/journal/7105328027"),
                "文献号与文献页地址照旧（带 sign 的地址每次检索都变，不能当键）");
        check(rows.get(0).abstractText.startsWith("这是 真摘要 的一段，长度够入库比对。"),
                "摘要照 Xml.stripTags 的原样进来（标签处补一个空格是全局行为，不在这一路改）");
        check(rows.get(0).abstractText.contains("基于A/B的方法") && rows.get(0).abstractText.contains("张三")
                        && rows.get(0).abstractText.contains("2021年第8期")
                        && rows.get(0).abstractText.contains("关键词 深度学习"),
                "题名、作者、年期、关键词跟着摘要一起进可比对文本");

        ArrayList<PaperSources.Candidate> plain = PaperSources.parseCqvip(PAGE_NO_STATE, 10);
        check(plain.get(0).source.title.equals("计算机应用 2021年第8期"),
                "状态缺席时题名照旧署成\"刊名 年 期\"，0.7.3 的形状一个字没变");
        check(plain.get(1).source.title.equals("学位论文 东北大学 2020年第3期"),
                "状态缺席时的学位论文也照旧顶\"学位论文\"");
        check(plain.get(0).abstractText.equals("这是 真摘要 的一段，长度够入库比对。"),
                "状态缺席时不多交一个字——半成品式的追加比少交更糟");

        String legacy = "tests/samples/cqvip-search.html";
        if (new File(legacy).isFile()) {
            String html = read(legacy);
            check(CqvipState.parse(html).isEmpty(), legacy + " 这份老夹具本来就没有 __NUXT__ 状态");
            ArrayList<PaperSources.Candidate> old = PaperSources.parseCqvip(html, 20);
            check(!old.isEmpty(), "老夹具照样解得开行");
            for (PaperSources.Candidate row : old) {
                check(!row.abstractText.contains("关键词"), "老夹具这行没被追加关键词：" + row.source.id);
                /* 老夹具的刊名链接自带《》，光看"有没有书名号"会误判；新署名把出处放在题名之后，
                   书名号只可能落在串首，所以验"只出现一次"。 */
                check(row.source.title.indexOf('《') == row.source.title.lastIndexOf('《'),
                        "老夹具的题名仍按 0.7.3 署成刊名行，没冠上真题名：" + row.source.title);
            }
        } else System.out.println("SKIP 老夹具（缺 " + legacy + "）");

        String live = args.length > 0 ? args[0] : "tests/samples/cqvip-search-titled.html";
        if (new File(live).isFile()) {
            String html = read(live);
            LinkedHashMap<String, CqvipState.Record> fresh = CqvipState.parse(html);
            check(!fresh.isEmpty(), "真实检索页的状态解得开");
            int named = 0;
            for (PaperSources.Candidate row : PaperSources.parseCqvip(html, 20)) {
                CqvipState.Record known = CqvipState.find(fresh, row.source.id);
                if (known == null || known.title.isEmpty()) continue;
                check(row.source.title.startsWith(known.title), "真题名署在这一行上：" + known.title);
                check(row.abstractText.contains(known.keywords.replace(";", " ")),
                        "关键词跟着摘要进可比对文本：" + known.title);
                named++;
            }
            check(named > 0, "真实页里至少一条记录署上了真题名（服务端改了结构时这条先响）");
        } else System.out.println("SKIP 真实字节一遍（缺 " + live + "）");

        System.out.println("SUMMARY " + passed + " assertions passed (hand-written Nuxt state + real cqvip pages).");
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        passed++;
        System.out.println("PASS " + what);
    }
}

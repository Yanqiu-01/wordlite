package com.rikkahub.wordlite;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * CnkiSearch 回归：表单编码与页码夹取、请求头、data-fn 去重、会议/期刊/学位论文三种条目页路由、
 * 高亮标签去掉之后不能在中文里留空格、脏页面只给空表不许抛异常，以及 0.6.1 的中文查询短语整形
 * （去标点、去停用词、术语块与数字串整体保住、分族给长度，而 0.5.4 那条"最多两个词"不许退）。
 * 全程只读 tests/samples/cnki-search.html 与 tests/corpus 的两份真实语料，不发网络请求。
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
            shapingPunctuation();
            shapingStopwords();
            families();
            shapedFixtures();
            wholeWindow();
            headers();
            routing();
            parsed(html);
            highlights(html);
            dedupe(html);
            dissertation();
            edgesSurviveTerms();
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
        // 0.6.1 之前这条期望是 "AI 研究"：单字词"与"照旧靠长度出局，"研究"现在按停用词出局。
        check("AI 展望".equals(CnkiSearch.narrow("AI 研究 与 展望")),
                "单字词不参与，停用词\"研究\"也出局，剩下的仍然只留两个");
        check("AI 展望".equals(CnkiSearch.narrow("AI 与 展望 评述")),
                "去掉停用词之后，单字词不参与这条仍然成立：三个双字词只留两个");
        check(CnkiSearch.form("宽禁带半导体 封装 互连材料 可靠性", 3).contains("Content="
                + java.net.URLEncoder.encode("宽禁带半导体 互连材料", "UTF-8") + "&Type=0"),
                "form 发出去的就是收窄后的两个词");
        narrowingGuard(new String[] {
                "宽禁带半导体 封装 互连材料 可靠性",
                "基于宽禁带半导体的封装方法",
                "GB/T 12345-2020 陶瓷 烧结",
                "0.05 mm 孔隙率",
                "以SiC、GaN为代表的宽禁带半导体具有较高的击穿电场",
                "孔隙率过高、孔径分布不均或液相填充不足",
                "epoxycyclohexylmethyl bonding strength",
                "the reliability of SiC sintered silver joints",
        }, "静态窗口：");
        narrowWordCount("宽禁带半导体 封装 互连材料 可靠性");
        narrowWordCount("基于宽禁带半导体的封装方法");
        narrowWordCount("孔隙率过高、孔径分布不均或液相填充不足");
        narrowWordCount("基于宽禁带半导体器件封装互连材料的可靠性研究是当前电力电子封装领域研究的热点问题");
    }


    /**
     * 0.5.4 那条实测结论的守卫：整形做了再多事情，输出也不许比"两个最具体的词"更宽。
     * 词块个数看 picks()——"0.05 mm"、"GB/T 12345" 自己就带一个空格，按空白切会把一个词块数成两个词。
     */
    private static void narrowingGuard(String[] windows, String tag) {
        for (int i = 0; i < windows.length; i++) {
            check(CnkiSearch.picks(windows[i], CnkiSearch.FAMILY_CN).size() <= 2,
                    tag + "第 " + (i + 1) + " 个窗口整形后不超过两个词块");
            check(CnkiSearch.picks(windows[i], CnkiSearch.FAMILY_LATIN).size() <= 2,
                    tag + "第 " + (i + 1) + " 个窗口换成英文档也不超过两个词块");
        }
    }

    /** 纯中文窗口没有带空格的术语块，可以按空白直接数词，这才是 0.5.4 那句"两个词"的字面口径。 */
    private static void narrowWordCount(String window) {
        String out = CnkiSearch.narrow(window);
        check(!out.isEmpty() && out.trim().split("\\s+").length <= 2,
                "纯中文窗口\"" + window + "\"发出去的不超过两个词");
    }

    /** 0.6.1 之一：检索词里不带中英文标点，但术语块和数字串内部的斜杠、连字符、小数点原样保住。 */
    private static void shapingPunctuation() {
        check("宽禁带半导体 互连材料".equals(CnkiSearch.narrow("《宽禁带半导体》、封装，互连材料")),
                "书名号与顿号被去掉，仍然只留最长的两个词");
        check("互连材料 TLP".equals(CnkiSearch.narrow("——互连材料……可靠性（TLP）")),
                "破折号、省略号、括号都不进查询串");
        check("互连材料 TLP".equals(CnkiSearch.narrow("互连材料, 可靠性. (TLP)")),
                "英文逗号句点和半角括号一样只是分隔符");
        check(clean(CnkiSearch.narrow("《宽禁带半导体》、封装，互连材料"))
                        && clean(CnkiSearch.narrow("——互连材料……可靠性（TLP）"))
                        && clean(CnkiSearch.narrow("互连材料, 可靠性. (TLP)")),
                "三种标点写法整形之后一个标点都不剩");

        check("GB/T 12345".equals(CnkiSearch.narrow("GB/T 12345")),
                "标准号整体是一个词块：斜杠和中间的空格都在块内");
        check("GB/T 12345-2020 陶瓷".equals(CnkiSearch.narrow("GB/T 12345-2020 陶瓷 烧结")),
                "带年份的标准号占一席，另一个词块留给中文术语");
        check("0.05 mm 孔隙率".equals(CnkiSearch.narrow("0.05 mm 孔隙率")),
                "带单位的测量值不被空格劈成 \"0.05\" 和 \"mm\"");
        check("Zn0.9Fe2.1O4 陶瓷".equals(CnkiSearch.narrow("Zn0.9Fe2.1O4 陶瓷 烧结")),
                "带小数的化学式整体保住，十二个字符一个不丢");
        check("PM2.5 质量浓度".equals(CnkiSearch.narrow("PM2.5 质量浓度 颗粒物")),
                "PM2.5 的小数点不是分隔符");
        check("SAC305 多孔铜".equals(CnkiSearch.narrow("SAC305 焊料 多孔铜")),
                "牌号里的数字跟着牌号走，短的一般词先让位");
        check("Cu-Sn 金属间化合物".equals(CnkiSearch.narrow("Cu-Sn 金属间化合物 生长")),
                "连字符术语块不被切成 \"Cu\" 和 \"Sn\"");
        check("Cu-Sn/Ag-Sn 反应".equals(CnkiSearch.narrow("Cu-Sn/Ag-Sn 反应 界面")),
                "连字符和斜杠混排的术语块照样是一个整块");
        check("SEM/EDS 观察".equals(CnkiSearch.narrow("SEM/EDS 观察 界面")),
                "仪器缩写串 \"SEM/EDS\" 是一个词而不是两个");
        check("I-V 特性".equals(CnkiSearch.narrow("I-V 特性 测试")),
                "单字母加连字符的形状（I-V 曲线）不被切碎");
        check("HTCC 基板微波性能".equals(CnkiSearch.narrow("HTCC 基板微波性能")),
                "全大写缩写和中文术语各占一席");
        check("1000℃ 烧结".equals(CnkiSearch.narrow("1000℃ 烧结 银浆"))
                        && "1000℃".equals(CnkiSearch.termsOf("1000℃ 烧结 银浆", CnkiSearch.FAMILY_CN).get(0)),
                "温度数字连着单位度是一个词块");
        check("20 vol% 添加".equals(CnkiSearch.narrow("20 vol% 添加 环氧"))
                        && "20 vol%".equals(CnkiSearch.termsOf("20 vol% 添加 环氧", CnkiSearch.FAMILY_CN).get(0)),
                "vol% 这种带百分号的配比写法整体保住");

        java.util.ArrayList<String> terms = CnkiSearch.termsOf("GB/T 12345-2020", CnkiSearch.FAMILY_CN);
        check(terms.size() == 1 && "GB/T 12345-2020".equals(terms.get(0)),
                "抽词阶段就已经把带年份的标准号看成一个词");
        check(CnkiSearch.termsOf("Cu-Sn/Ag-Sn 反应 界面", CnkiSearch.FAMILY_CN).get(0).equals("Cu-Sn/Ag-Sn")
                        && CnkiSearch.termsOf("Zn0.9Fe2.1O4 陶瓷", CnkiSearch.FAMILY_CN).get(0).equals("Zn0.9Fe2.1O4"),
                "术语块在抽词阶段就没有被劈开，不是选词时才补救");
        java.util.ArrayList<String> numbers = CnkiSearch.termsOf(
                "机动车数量由2014年的2.79亿辆增长到2025年的4.6亿辆", CnkiSearch.FAMILY_CN);
        check(numbers.contains("2014") && numbers.contains("2.79") && numbers.contains("4.6"),
                "年份、小数各自成块，没被相邻中文粘住也没被劈开");
        check(!CnkiSearch.narrow("多孔铜具有较高的比表面积、良好的导电导热性能、较低的材料成本以及可调控的孔隙结构[11]")
                        .contains("11"),
                "文末的引文序号 [11] 不会摇身变成查询词");
    }

    /** 0.6.1 之二：去停用词。基于X的Y方法 只该留下 X 和 Y，而且不能为了去词把术语劈坏。 */
    private static void shapingStopwords() {
        check("宽禁带半导体 封装".equals(CnkiSearch.narrow("基于宽禁带半导体的封装方法")),
                "\"基于\"\"的\"\"方法\"全清掉，中间的术语 X 一个字没动");
        check("宽禁带半导体".equals(CnkiSearch.narrow("方法 研究 分析 宽禁带半导体")),
                "三个停用词一起消失，只剩那个术语");
        check("可靠性 稳定性".equals(CnkiSearch.narrow("可靠性 与 稳定性")),
                "连接词\"与\"独立成词时被删");
        check("调控孔隙率".equals(CnkiSearch.narrow("通过调控孔隙率")),
                "句首的\"通过\"被切掉，剩下的术语整块留下");
        check("优化孔隙率".equals(CnkiSearch.narrow("通过优化孔隙率")),
                "同一把刀在别的句首也一样：\"通过\"出局，\"优化孔隙率\"不动");
        check(!CnkiSearch.narrow("通过调控孔隙率实现高性能").contains("通过"),
                "\"通过\"没有混进任何一个词块里");
        check("界面反应 性能退化".equals(CnkiSearch.narrow("界面反应与性能退化")),
                "\"与\"在中间下刀，两边的术语都完整");
        check(CnkiSearch.narrow("界面反应与性能退化").contains("性能"),
                "\"能\"是停用词但没当刀用，\"性能\"没被劈成\"性\"");
        check("饱和度测试".equals(CnkiSearch.narrow("饱和度测试")),
                "\"和\"是停用词但没当刀用，\"饱和\"没被劈成\"饱\"加\"度测试\"");
        check("在线检测系统".equals(CnkiSearch.narrow("在线检测系统")),
                "\"在\"是停用词但没当刀用，\"在线\"整块留下");
        check("等温凝固过程".equals(CnkiSearch.narrow("等温凝固过程")),
                "\"等\"\"过\"都是停用词但都没当刀用，\"等温\"和\"过程\"完好");
        check("最小二乘拟合".equals(CnkiSearch.narrow("最小二乘拟合")),
                "\"最\"是停用词但没当刀用，\"最小二乘\"没被啃掉一个字");
        check("蠕变行为".equals(CnkiSearch.narrow("蠕变行为")),
                "\"为\"是停用词但没当刀用，\"行为\"这个术语还在");
        check("参与率".equals(CnkiSearch.narrow("参与率")),
                "切完会剩下单字的刀不下刀，\"参与率\"整块保留");
        check("研究方法".equals(CnkiSearch.narrow("研究方法")),
                "整窗都是停用词时退回 0.5.4 的旧路径，绝不退化成空 Content");
        check(CnkiSearch.termsOf("研究方法", CnkiSearch.FAMILY_CN).isEmpty(),
                "抽词阶段确实把\"研究\"和\"方法\"都清光了，退回的不是整形结果");
        check("".equals(CnkiSearch.narrow("的 了 与")), "整窗只有独立成词的停用词时仍是空串，和现状一致");
        check("孔隙率 孔径".equals(CnkiSearch.narrow("孔隙率 孔径 分布 情况")),
                "\"情况\"这类多字停用词出局，两个词块留给更专门的词");
    }

    /** 0.6.1 之三：同一个窗口分族给长度，中文库窄、英文库宽；词数上限不分族。 */
    private static void families() {
        check(CnkiSearch.familyOf("宽禁带半导体 封装") == CnkiSearch.FAMILY_CN, "含汉字的窗口走中文档");
        check(CnkiSearch.familyOf("SiC sintered silver joints") == CnkiSearch.FAMILY_LATIN, "纯拉丁窗口走英文档");
        check(CnkiSearch.familyOf("SiC器件功率循环") == CnkiSearch.FAMILY_CN, "中英混排按中文档，术语块另有 16 字符的额度");
        check(CnkiSearch.familyOf("") == CnkiSearch.FAMILY_CN && CnkiSearch.familyOf(null) == CnkiSearch.FAMILY_CN,
                "空窗口按中文档，认不出文字族时不该走更宽的那一档");
        String longWord = "epoxycyclohexylmethyl bonding strength";
        String cn = CnkiSearch.narrow(longWord, CnkiSearch.FAMILY_CN);
        String latin = CnkiSearch.narrow(longWord, CnkiSearch.FAMILY_LATIN);
        check("epoxycyclohexylm strength".equals(cn), "中文档把 21 字符的长词夹到 16 字符");
        check("epoxycyclohexylmethyl strength".equals(latin), "英文档留得住 21 字符的整词");
        check(latin.length() > cn.length() && cn.length() < longWord.length(),
                "同一个窗口英文档就是比中文档宽，而且两档都比原文短");
        check("reliability sintered".equals(CnkiSearch.narrow(
                        "the reliability of SiC sintered silver joints", CnkiSearch.FAMILY_LATIN)),
                "英文档先清掉 the/of 这类英文停用词，再按长度留两个词");
        check(!CnkiSearch.narrow("the reliability of SiC sintered silver joints", CnkiSearch.FAMILY_LATIN)
                        .startsWith("the "),
                "英文停用词不会被当成查询词发出去");
        check(CnkiSearch.narrow("宽禁带半导体 封装 互连材料 可靠性", CnkiSearch.FAMILY_CN)
                        .equals(CnkiSearch.narrow("宽禁带半导体 封装 互连材料 可靠性", CnkiSearch.FAMILY_LATIN)),
                "纯中文窗口在两档下结果完全一样，分族只动长度不动收窄");
    }

    /** 0.6.1 之四：真实语料。夹具里的真句子整形完仍然带着术语和数字，且不带标点与停用词。 */
    private static void shapedFixtures() {
        String gap = CnkiSearch.narrow(sentence(PROSE, "宽禁带半导体"));
        check(gap.contains("SiC"), "real-prose 的真实句子：SiC 这个术语进了查询串");
        check(gap.contains("宽禁带半导体"),
                "real-prose 的真实句子：六字中文术语整块留下，和缩写各占一席");
        check(clean(gap), "real-prose 的真实句子：顿号逗号方括号一个没带进查询串");
        check(!gap.contains("具有") && !gap.contains("逐渐"),
                "real-prose 的真实句子：\"具有\"这类虚词没混进查询串");

        String imc = CnkiSearch.narrow(sentence(PROSE, "Cu-Sn"));
        check(imc.contains("Cu-Sn"), "real-prose 的真实句子：Cu-Sn 连着连字符进了查询串");
        check(clean(imc), "real-prose 的真实句子：逗号分号方括号全被当成分隔符");

        String tlp = CnkiSearch.narrow(sentence(PROSE, "瞬态液相连接"));
        check(tlp.contains("Transient") || tlp.contains("TLP"),
                "real-prose 的真实句子：括号里的英文术语与缩写没有因为括号丢掉");
        check(clean(tlp), "real-prose 的真实句子：全角括号在块外，只做分隔符");

        String porous = CnkiSearch.narrow(sentence(PROSE, "多孔铜具有较高的比表面积"));
        check(porous.contains("性能"), "real-prose 的真实句子：\"性能\"没被单字停用词劈坏");
        check(clean(porous) && !porous.contains("11"), "real-prose 的真实句子：引文序号 [11] 不是查询词");

        String pm = CnkiSearch.narrow(sentence(CROSS, "PM2.5"));
        check(pm.contains("PM2.5"), "cnki-cross 的真实句子：PM2.5 整体进了查询串");
        check(clean(pm), "cnki-cross 的真实句子：中英文逗号混排也留不下标点");

        String sar = CnkiSearch.narrow(sentence(CROSS, "SAR"));
        check(sar.contains("SAR"), "cnki-cross 的真实句子：括号里的 SAR 保住了");
        String slam = CnkiSearch.narrow(sentence(CROSS, "SLAM"));
        check(slam.contains("SLAM"), "cnki-cross 的真实句子：SLAM 保住了");
        check(clean(sar) && clean(slam), "cnki-cross 的真实句子：两条都不带标点");

        String cars = sentence(CROSS, "2.79");
        java.util.ArrayList<String> numbers = CnkiSearch.termsOf(cars, CnkiSearch.FAMILY_CN);
        check(numbers.contains("2.79") && numbers.contains("4.6"),
                "cnki-cross 的真实句子：2.79 与 4.6 在抽词阶段就是完整的数字块");
        narrowingGuard(new String[] { sentence(PROSE, "宽禁带半导体"), sentence(PROSE, "Cu-Sn"),
                sentence(PROSE, "瞬态液相连接"), sentence(PROSE, "多孔铜具有较高的比表面积"),
                sentence(CROSS, "PM2.5"), sentence(CROSS, "SAR"), sentence(CROSS, "SLAM"), cars, },
                "真实窗口：");
    }
    /** 反证：整段四十个字的窗口原样丢进去，结果只能和"两个最具体的词"一致或更窄。 */
    private static void wholeWindow() {
        String window = "基于宽禁带半导体器件封装互连材料的可靠性研究是当前电力电子封装领域研究的热点问题";
        check(window.length() == 40, "反证用的窗口正好四十字，没有空格也没有标点");
        String out = CnkiSearch.narrow(window);
        check(out.length() < window.length(), "整形后的查询串比整段窗口短，不会比现状更宽");
        check(out.contains("宽禁带半导体"), "六字术语整块留在查询串里");
        check(out.contains("封装互连"), "第二个词块落在封装互连这一段上");
        check(!out.contains("基于") && !out.contains("研究") && !out.contains("热点"),
                "\"基于\"\"研究\"\"热点\"这些框架词都不在查询串里");
        check(out.trim().split("\\s+").length <= 2, "四十字窗口最后只发出两个词");
        check(!out.equals(window), "输出不是原窗口");
    }    private static void headers() {
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

    /** 真实语料：0.5.4 的收窄和 0.6.1 的整形都是从这两份文件的真句子里试出来的，换夹具就该响。 */
    private static final String PROSE = "tests/corpus/real-prose.txt";
    private static final String CROSS = "tests/corpus/cnki-cross.txt";

    /**
     * 取语料里含关键词的第一句（只按中文句号切，逗号顿号括号留在句子里）。
     * 不写死整句是为了让语料重排时断言仍然指着同一类句子，而不是先撞在"找不到夹具"上。
     */
    private static String sentence(String path, String keyword) {
        String[] lines = read(path).split("\n");
        for (int i = 0; i < lines.length; i++) {
            int at = lines[i].indexOf(keyword);
            if (at < 0) continue;
            int from = lines[i].lastIndexOf('\u3002', at);
            int to = lines[i].indexOf('\u3002', at + keyword.length());
            String out = lines[i].substring(from < 0 ? 0 : from + 1, to < 0 ? lines[i].length() : to).trim();
            if (out.contains(keyword)) return out;
        }
        throw new IllegalStateException("语料 " + path + " 里找不到关键词 " + keyword);
    }

    /**
     * 查询串里不该有标点。表里故意不收 - / . 和百分号与度数：它们在术语块内部
     * （Cu-Sn、GB/T 12345、2.79、20 vol%、1000℃），收了就等于把整条 0.6.1 往回退。
     */
    private static final String PUNCT = "，。、；：？！”“‘’《》〈〉（）【】〔〕…—·,;:?!()[]{}<>\"'`~|@#$^&*+=_　";

    private static boolean clean(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) if (PUNCT.indexOf(value.charAt(i)) >= 0) return false;
        return true;
    }
    private static boolean ascii(String value) {
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) > 127) return false;
        return true;
    }

    /**
     * 两端剥语法残渣这一步实测踩过两次：表里带"应"时 "界面反应" 被剥成 "界面反"，
     * 带"再"时 "再熔温度" 被剥成 "熔温度"。这两条加上几条同类的术语完整性检查，
     * 就是为了让那次改法不能悄悄回来。
     */
    private static void edgesSurviveTerms() {
        String reaction = CnkiSearch.narrow("界面反应与性能退化");
        check(reaction.contains("界面反应"), "\"界面反应\"不许被尾端的\"应\"剥成\"界面反\"，实测 " + reaction);
        check(reaction.contains("性能退化"), "刀在\"与\"上，两边术语都得整块活着，实测 " + reaction);
        String remelt = CnkiSearch.narrow("连接完成后应具有较高的再熔温度和高温稳定性");
        check(remelt.contains("再熔温度"), "\"再熔温度\"不许被剥成\"熔温度\"，实测 " + remelt);
        check(!remelt.contains("具有"), "\"具有\"这类动词不许留在查询块里，实测 " + remelt);
        String fem = CnkiSearch.narrow("基于有限元方法的应力分析");
        check(fem.contains("有限元"), "\"有限元\"是术语，尾端的\"元\"与句首的\"基于\"都不许伤它，实测 " + fem);
        String saturation = CnkiSearch.narrow("界面饱和应力随温度变化");
        check(saturation.contains("饱和"), "\"饱和\"不许因为单字停用词\"和\"被劈坏，实测 " + saturation);
        String fleet = CnkiSearch.narrow("汽车保有量持续增加");
        check(fleet.contains("汽车保有量"), "\"持续\"下刀之后不许留下 \"汽车保有量持\" 这种半截词，实测 " + fleet);
        String aperture = CnkiSearch.narrow("孔径实现高性能");
        check(aperture.contains("高性能"), "\"实现\"下刀要给出 \"高性能\" 而不是 \"高性\"，实测 " + aperture);
        check(CnkiSearch.picks("界面反应与性能退化", CnkiSearch.FAMILY_CN).size() <= 2,
                "补上这些刀之后，收窄上限仍然是两个词");
    }
    private static String read(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), UTF8);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("missing fixture " + path, error);
        }
    }
}

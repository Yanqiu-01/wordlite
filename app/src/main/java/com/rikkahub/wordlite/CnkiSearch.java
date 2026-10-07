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

    /* 0.6.1 分族：含汉字的窗口发往中文库那一族，纯拉丁窗口是英文文献那一族。两族词数上限相同。 */
    static final int FAMILY_CN = 0;
    static final int FAMILY_LATIN = 1;
    /** 0.5.4 实测的收窄：三个词以上服务端换的是一批固定填充条目，这条不分族，也不许放松。 */
    private static final int MAX_TERMS = 2;
    /** 一个中文块最长 8 个汉字，超了就按 SPLIT_CHARS 重切（见 splitRun 与 addEven）。 */
    private static final int CJK_CHARS = 8;
    /** 重切的一刀长度：7 字的术语不会被切（8 字以内不动），14 字的块切成 6+8（见 addEven）。 */
    private static final int SPLIT_CHARS = 6;
    private static final int MIN_TERM_CHARS = 2;
    /** 术语块与总预算的分族长度：中文库窄，英文库宽。 */
    private static final int CN_BLOCK_CHARS = 16;
    private static final int CN_BUDGET = 28;
    private static final int LATIN_BLOCK_CHARS = 32;
    private static final int LATIN_BUDGET = 60;
    /** 带字母的块在同族里压过只有数字和单位的块。 */
    private static final int LATIN_BONUS = 4;
    private static final int GROUP_CJK = 0;
    private static final int GROUP_BLOCK = 1;
    private static final int GROUP_NUMBER = 2;

    /* 术语块与数字串的取法。顺序有意义：标准号必须排在拉丁块前面，否则 "GB/T 12345" 先被拉丁块
       吃掉 "GB/T" 那一截；带单位的数字必须排在裸数字前面，否则 "0.05 mm" 会被劈成 "0.05" 和 "mm"。
       单位表是有据的小表：MPa、vol、wt、rpm、℃、mm、μm 在 tests/corpus 与 tests/samples 的
       夹具里各出现过 4 到 21 行，GHz/kHz 属于顺手加的。它不该越长越好——多一个单位就多一次
       把 "2024 study" 咬成 "2024 stu" 的机会，所以块尾带 (?![A-Za-z0-9]) 卡死。 */
    private static final String STANDARD = "(?:[A-Za-z]{2,6}[ ]?/[ ]?){1,3}[A-Za-z]{0,6}[ ]?\\d[\\d.\\-]*";
    private static final String UNIT = "%|\\u2030|\\u2103|\\u00b0C|\\u00b0F|MPa|GPa|kPa|Pa|kV|kWh|kHz|MHz|GHz"
            + "|vol|wt|rpm|nm|\\u03bcm|mm|cm|km|mg|kg|mL|min";
    private static final String MEASURE = "\\d+(?:[.,]\\d+)?[ ]?(?:" + UNIT + ")(?:%|\\u2030)?(?![A-Za-z0-9])";
    private static final String BLOCK = "[A-Za-z][A-Za-z0-9.]*(?:[-+/][A-Za-z0-9.]+)*";
    private static final String NUMBER = "\\d+(?:[.,]\\d+)?(?:-\\d+)?";
    /** 引文序号：知网摘要里 [1]、[11]、[1-3] 满地都是，那是标点不是词，整个吞掉，别让它当上查询词。 */
    private static final String CITATION = "\\[\\d{1,3}(?:[ ]?[-\\u2013,\\uFF0C][ ]?\\d{1,3}){0,3}\\]";
    private static final Pattern TERM = Pattern.compile(
            CITATION + "|" + STANDARD + "|" + MEASURE + "|" + BLOCK + "|" + NUMBER + "|[\\u4e00-\\u9fa5]+");

    /* 停用词分两张表，尺度完全不同。整词表（WHOLE_STOPS）接 PaperSources 那份已经在语料链上跑着的
       中英文停用词，再加七个知网场景的口头禅——"基于/方法/研究/分析"正是 "基于X的Y
       方法" 那层壳子，"探讨/影响/作用"是题名和小结里最不区分两篇论文的三个词。
       在连续中文里下刀的另一张表只收整词表里长度 ≥2 的那些词，外加 SHORT_CUTS 十一个单字：
       剩下的单字停用词（能/和/在/对/过/等/为/最/者/可）一旦当刀用，"性能""饱和""在线""对称"
       "过程""等温""行为""最小""作者""可靠" 全部会被劈坏，它们只在独立成词时才删。
       "是"敢进这张表，是因为技术术语里没有一个以它起头，切完只是把 "是移动机器人自主化" 这种
       系动词开头的碎块让位给后面的真术语。 */
    /* 0.7.2 扩刀：真人论文窗口里那些"动词/系词/连词"块实测会整块变成查询词
       （"被视为一种有前景""不断朝着高功率密度""孔径实现高性能"），而知网对这种串根本不匹配，
       返回的是固定填充条目（11 个真实窗口实测对题 1/11）。这些词只在块内下刀，
       且切完两侧都不许剩单字，所以 "高性能""可靠性" 这类不会被劈坏。 */
    private static final String[] CNKI_STOPWORDS = { "基于", "方法", "研究", "分析", "探讨", "影响", "作用",
            "视为", "一种", "实现", "完成", "具有", "能够", "可以", "需要", "要求", "成为", "作为",
            "随着", "朝着", "不断", "增加", "采用", "分别", "上述", "以及", "并且", "但是", "因此",
            "其中", "对于", "关于", "进行", "通过", "此外", "从而", "以便", "本文", "目前",
            "持续", "同时", "相应" };
    private static final String SHORT_CUTS = "\u7684\u4e86\u4e4b\u4e0e\u53ca\u6216\u800c\u5176\u6240\u4e5f\u662f";
    private static final ArrayList<String> LONG_STOPS = new ArrayList<String>();
    private static final java.util.HashSet<String> WHOLE_STOPS = new java.util.HashSet<String>();
    private static final java.util.HashSet<String> ENGLISH_STOPS = new java.util.HashSet<String>();
    static {
        for (int i = 0; i < PaperSources.CHINESE_STOPWORDS.length; i++) addStop(LONG_STOPS, PaperSources.CHINESE_STOPWORDS[i]);
        for (int i = 0; i < CNKI_STOPWORDS.length; i++) addStop(LONG_STOPS, CNKI_STOPWORDS[i]);
        for (int i = 0; i < PaperSources.ENGLISH_STOPWORDS.length; i++) {
            ENGLISH_STOPS.add(PaperSources.ENGLISH_STOPWORDS[i].toLowerCase(Locale.ROOT));
        }
    }

    /** 进整词表；长度 ≥2 的再插进下刀表，插入位置按长度从长到短，长词优先匹配。 */
    private static void addStop(ArrayList<String> cuts, String word) {
        if (word == null || word.isEmpty() || WHOLE_STOPS.contains(word)) return;
        WHOLE_STOPS.add(word);
        if (word.length() < 2) return;
        int at = 0;
        while (at < cuts.size() && cuts.get(at).length() >= word.length()) at++;
        cuts.add(at, word);
    }

    /**
     * 0.6.1 中文检索短语整形。四步：先把术语块与数字串整体取出来，块外的中英文标点自动成了分隔符
     * （这就是去标点），连续中文再按停用词下刀切词（去停用词），最后仍按 0.5.4 那条实测收窄到两个词。
     *
     * 收窄这条底线一寸没让：输出最多 MAX_TERMS 个词，三个词以上服务端换回来的是那批固定填充条目，
     * 见 legacy()。整形一个词都切不出来时退回 legacy()，绝不发空 Content——空 Content 等于不加约束，
     * 比原来那条长查询还宽。
     */
    static String narrow(String phrase) {
        return narrow(phrase, familyOf(phrase));
    }

    /** 分族入口：档位通常由 familyOf 选，也可以显式指定，同一条整形链，词数上限不分族。 */
    static String narrow(String phrase, int family) {
        if (phrase == null) return "";
        String shaped = shape(phrase, family);
        return shaped.isEmpty() ? legacy(phrase) : shaped;
    }

    /**
     * 分族：含一个汉字就算中文这一族（本类那个检索框接的正是中文库），刀口要紧——单块 8 个汉字、
     * 术语块 16 字符、总预算 28 字符。纯拉丁窗口是英文文献那一族，一个化学式或长术语自己就占二十多个
     * 字符，用 8 字刀会把 "epoxycyclohexylmethyl" 这类长词劈成两截，所以那一族放到 32 字符、预算 60。
     * 空串按中文档处理：中文窗口才是这条链的常态。
     */
    static int familyOf(String phrase) {
        if (phrase == null || phrase.trim().isEmpty()) return FAMILY_CN;
        for (int i = 0; i < phrase.length(); i++) if (isHan(phrase.charAt(i))) return FAMILY_CN;
        return FAMILY_LATIN;
    }

    /**
     * 整形主体：抽词（术语块与数字串整体保住）→ 停用词切词 → 两个槽各给一族 → 按原顺序拼并夹到本档预算。
     *
     * 两个槽一条一个：槽一给术语块（SiC、SAC305、GB/T 12345、0.05 mm），槽二给中文块
     * （宽禁带半导体、互连材料）。哪一族缺位就由另一族和纯数字补上。这样"以SiC、GaN为代表的宽禁带半导体"
     * 给的是 "SiC 宽禁带半导体"——术语和主题各占一席，而不是两个缩写把中文主题挤出去。
     * 纯中文窗口里根本没有术语块，两槽都落在中文块上，选法就退化成 0.5.4 那句"最长的两个"，逐字不变。
     */
    private static String shape(String phrase, int family) {
        ArrayList<String> picked = picks(phrase, family);
        int budget = family == FAMILY_LATIN ? LATIN_BUDGET : CN_BUDGET;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < picked.size(); i++) {
            String term = picked.get(i);
            if (out.length() > 0) out.append(' ');
            int room = budget - out.length();
            out.append(term.length() <= room ? term : term.substring(0, Math.max(0, room)));
        }
        return out.toString().trim();
    }

    /** 选出来的两个词块，按原顺序。单独拆出来是给回归留一个能直接数词块的口子——"0.05 mm" 自己就带空格。 */
    static ArrayList<String> picks(String phrase, int family) {
        ArrayList<String> terms = termsOf(phrase, family);
        ArrayList<String> out = new ArrayList<String>();
        if (terms.isEmpty()) return out;
        int block = bestOf(terms, GROUP_BLOCK, -1);
        int cjk = bestOf(terms, GROUP_CJK, -1);
        int number = bestOf(terms, GROUP_NUMBER, -1);
        ArrayList<Integer> ranked = new ArrayList<Integer>();
        addIndex(ranked, block);
        addIndex(ranked, cjk);
        addIndex(ranked, bestOf(terms, GROUP_BLOCK, block));
        addIndex(ranked, bestOf(terms, GROUP_CJK, cjk));
        /* 纯数字排最后：一个孤零零的 "2024" 不配把第二个中文术语挤出查询串。 */
        addIndex(ranked, number);
        addIndex(ranked, bestOf(terms, GROUP_NUMBER, number));
        ArrayList<Integer> taken = new ArrayList<Integer>();
        for (int i = 0; i < ranked.size() && taken.size() < MAX_TERMS; i++) {
            Integer at = Integer.valueOf(ranked.get(i).intValue());
            if (!taken.contains(at)) taken.add(at);
        }
        java.util.Collections.sort(taken);
        for (int i = 0; i < taken.size(); i++) out.add(terms.get(taken.get(i).intValue()));
        return out;
    }

    /**
     * 抽词。一次 find 扫描同时做完两件事：块外的书名号、括号、顿号、破折号、中英文逗号自动不进任何词，
     * 块内的斜杠、连字符和小数点原样保住——"GB/T 12345"、"Cu-Sn/Ag-Sn"、"Zn0.9Fe2.1O4"、"0.05 mm"
     * 各是一个词，不会被劈成 "GB"、"T"、"12345" 这种无意义碎片。
     */
    static ArrayList<String> termsOf(String phrase, int family) {
        ArrayList<String> out = new ArrayList<String>();
        Matcher m = TERM.matcher(phrase == null ? "" : phrase);
        while (m.find()) {
            String raw = m.group();
            if (raw.charAt(0) == '[') continue;
            if (isHan(raw.charAt(0))) addPieces(out, splitRun(raw));
            else addTerm(out, trimEdge(raw, blockCap(family)), family);
        }
        return out;
    }

    /**
     * 连续中文的切词。没有词典就没有真正的分词，这里只用两道有依据的刀：
     * 多字停用词（"基于/研究/方法/以及/具有"）几乎不会长在术语内部，任何位置都能下刀；
     * 单字停用词只敢用 SHORT_CUTS 那十一个字——"能/和/在/对/过/等/为/最"同样是停用词，
     * 但它们在连续中文里会先把 "性能"、"饱和"、"在线"、"对称"、"过程"、"等温"、"行为"、"最小" 劈成碎片，
     * 所以那八个字只在整个词正好是它时才删。两道刀都拒绝切出长度为 1 的碎片，"参与率" 因此整块留下。
     * 剩下的超长块从开头按 6 字一刀重切：14 字的 "宽禁带半导体器件封装互连材料" 切成 "宽禁带半导体" 和
     * "封装互连材料"，正好落在 0.5.4 那两个对题词上，而不是硬砍 8 字的 "宽禁带半导体封装"。
     */
    private static ArrayList<String> splitRun(String run) {
        ArrayList<String> pieces = new ArrayList<String>();
        pieces.add(run);
        pieces = cut(pieces, true);
        pieces = cut(pieces, false);
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < pieces.size(); i++) addEven(out, pieces.get(i));
        return out;
    }

    /** 一把刀扫一遍：longCut 为真用多字停用词表，为假只认 SHORT_CUTS 里那几个字。 */
    private static ArrayList<String> cut(ArrayList<String> pieces, boolean longCut) {
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < pieces.size(); i++) {
            String piece = pieces.get(i);
            int start = 0;
            int at = 0;
            while (at < piece.length()) {
                int length = longCut ? longStopLength(piece, at) : shortStopLength(piece, at);
                if (length <= 0) { at++; continue; }
                int left = at - start;
                int right = piece.length() - at - length;
                /* 切完剩一个单字的刀不下：宁可少切一刀，也不能把术语劈坏。 */
                if (left == 1 || right == 1) { at++; continue; }
                if (left > 0) out.add(piece.substring(start, at));
                start = at + length;
                at = start;
            }
            if (piece.length() > start) out.add(piece.substring(start));
        }
        return out;
    }

    /** 多字停用词按长度从长到短比，先长的，免得 "以及" 被 "以" 抢走半刀。 */
    private static int longStopLength(String text, int at) {
        for (int i = 0; i < LONG_STOPS.size(); i++) {
            String word = LONG_STOPS.get(i);
            if (text.regionMatches(at, word, 0, word.length())) return word.length();
        }
        return 0;
    }

    private static int shortStopLength(String text, int at) {
        return SHORT_CUTS.indexOf(text.charAt(at)) >= 0 ? 1 : 0;
    }

    /**
     * 超过 8 个汉字的块按 6 个一刀从头切，剩下的尾巴不超过 8 个就整块留着：
     * 14 字的 "宽禁带半导体器件封装互连材料" 切成 "宽禁带半导体" + "器件封装互连材料"，
     * 开头的六字术语正好整块留下，尾巴也不再被啃成 "材料" 这种两字碎块；对半分（7+7）留下的是
     * "宽禁带半导体器" 这种多一个字的东西。一刀 6 个而不是 8 个，是因为中文术语绝大多数是 2 到 6 字，
     * 6 个一刀撞对词边的机会最大，而且这块的开头一定是被保住的那一段。
     * 7 个字的 "金属间化合物" 走不到这一刀——8 字以内的块根本不切。尾巴不足两字就扔。
     */
    private static void addEven(ArrayList<String> out, String value) {
        if (value.length() < MIN_TERM_CHARS) return;
        if (value.length() <= CJK_CHARS) { addPiece(out, value); return; }
        int at = 0;
        while (value.length() - at > CJK_CHARS) {
            addPiece(out, value.substring(at, at + SPLIT_CHARS));
            at += SPLIT_CHARS;
        }
        addPiece(out, value.substring(at));
    }

    private static void addPieces(ArrayList<String> out, ArrayList<String> pieces) {
        for (int i = 0; i < pieces.size(); i++) addPiece(out, pieces.get(i));
    }

    /**
     * 词块两端的语法残渣。刀只在块内下，块首块尾的"被/应/后/时"这类会原地留在查询串里
     * （实测发出过 "连接完成后应"、"汽车保有量持"），所以两端反复剥；剥到不足 MIN_TERM_CHARS
     * 就停手，"前后"、"有限元"、"中间层" 因此不会被剥成单字。刻意不含 有/中/上/下：
     * "有限元" 是术语，剥掉 "有" 就变成 "限元" 这种没用的东西。
     */
    /* 这批字凡是出现在术语内部就不许剥，实测踩过两次：带 "应" 时 "界面反应" 被剥成 "界面反"，
       带 "再" 时 "再熔温度" 被剥成 "熔温度"；"使能"、"都市"、"更新"、"应用"、"方向" 同理。
       所以两端表刻意排除 应/再/使/都/更/从/向，只留那些在术语里几乎不出场的虚词。 */
    private static final String EDGE_LEAD = "被将要已也就把让给至该此并且";
    private static final String EDGE_TAIL = "要已也就较后来过去着而者后时";

    private static void addPiece(ArrayList<String> out, String value) {
        String trimmed = trimEdges(value);
        if (trimmed.length() < MIN_TERM_CHARS || WHOLE_STOPS.contains(trimmed) || out.contains(trimmed)) return;
        out.add(trimmed);
    }

    /** 两端剥语法残渣，最少留 MIN_TERM_CHARS 个字；整块都是残渣就交回空串。 */
    private static String trimEdges(String value) {
        int from = 0, to = value.length();
        boolean changed = true;
        while (changed && to - from > MIN_TERM_CHARS) {
            changed = false;
            while (to - from > MIN_TERM_CHARS && EDGE_LEAD.indexOf(value.charAt(from)) >= 0) { from++; changed = true; }
            while (to - from > MIN_TERM_CHARS && EDGE_TAIL.indexOf(value.charAt(to - 1)) >= 0) { to--; changed = true; }
        }
        return value.substring(from, to);
    }

    private static void addTerm(ArrayList<String> out, String value, int family) {
        if (value.length() < MIN_TERM_CHARS || out.contains(value)) return;
        String lower = value.toLowerCase(Locale.ROOT);
        if (WHOLE_STOPS.contains(lower)) return;
        /* 中文窗口里出现的拉丁词几乎都是术语，只有纯拉丁那一族才删英文停用词。 */
        if (family == FAMILY_LATIN && ENGLISH_STOPS.contains(lower)) return;
        out.add(value);
    }

    /** 一族里最好的那个：同分取先出现的，和 0.5.4 的旧口径一致。skip 传进来就取该族的第二个。 */
    private static int bestOf(ArrayList<String> terms, int group, int skip) {
        int best = -1;
        for (int i = 0; i < terms.size(); i++) {
            if (i == skip || group(terms.get(i)) != group) continue;
            if (best < 0 || score(terms.get(i)) > score(terms.get(best))) best = i;
        }
        return best;
    }

    private static void addIndex(ArrayList<Integer> out, int at) {
        if (at >= 0) out.add(Integer.valueOf(at));
    }

    /** 术语块 > 中文块 > 纯数字。哪个族有货就先占槽，纯数字只在前两族都空时才发出去。 */
    private static int group(String term) {
        if (isHan(term.charAt(0))) return GROUP_CJK;
        return isBareNumber(term) ? GROUP_NUMBER : GROUP_BLOCK;
    }

    /**
     * 只有数字和小数点才算裸数字——"2024"、"2.79" 这种年份和统计值当查询词没有区分度。
     * 带单位或字母的不算："1000℃"、"0.05 mm"、"20 vol%" 是参数，工艺温度差五十度就是另一篇论文，
     * 它们和 SiC 一样值得占一个槽。
     */
    private static boolean isBareNumber(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                if (c != '.' && c != ',') return false;
            }
        }
        return true;
    }

    /** 族内排序仍按长度，只是带字母的块压过只有数字和单位的块："SAC305" 排在 "280" 前面。 */
    private static int score(String term) {
        return term.length() + (hasLatinLetter(term) ? LATIN_BONUS : 0);
    }

    /** 块边缘的标点去掉（"Cu-" 就是 "Cu"），块内部的斜杠和连字符一个都不动。超长按本档术语块上限截。 */
    private static String trimEdge(String value, int cap) {
        int start = 0;
        int end = value.length();
        while (start < end && isEdgePunct(value.charAt(start))) start++;
        while (end > start && isEdgePunct(value.charAt(end - 1))) end--;
        String out = value.substring(start, end);
        return out.length() <= cap ? out : out.substring(0, cap);
    }

    private static int blockCap(int family) {
        return family == FAMILY_LATIN ? LATIN_BLOCK_CHARS : CN_BLOCK_CHARS;
    }

    private static boolean isEdgePunct(char c) {
        return c == '-' || c == '+' || c == '/' || c == '.' || c == '\u00b7' || c == '\u2014';
    }

    private static boolean isHan(char c) {
        return c >= '\u4e00' && c <= '\u9fa5';
    }

    private static boolean hasLatinLetter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') return true;
        }
        return false;
    }

    /**
     * 0.5.4 的收窄：最多留两个词，留的是最长的那两个（中文里词越长通常越专门）。两词以内原样发出。
     *
     * 为什么必须收窄——这是实测出来的。同一个表单只改 Content：
     * "宽禁带半导体 封装 互连材料 可靠性"、"封装 可靠性"、"宽禁带半导体 互连材料 可靠性" 三种写法，
     * 服务端都回同一批与查询词毫不相干的固定条目（奢侈品品牌与 Gucci、ZSM-5 催化裂解、云雾粒子探测）；
     * 换成两个最具体的词"宽禁带半导体 互连材料"，回来的才是块体碳化硅、氮化铝 HTCC 基板微波性能和可靠性
     * 这一堆对题的结果。而真正查无此项时它只回 430 字节的空片段——那批固定条目不是"空结果"，
     * 是没匹配上时塞进来的填充内容。照单全收等于往查重语料里塞五篇毫不相干的论文，重复率和来源榜一起脏。
     * 两词以内的查询走的是另一条正常路径，一律原样发出，别把本来能用的查询改坏。
     *
     * 0.6.1 之后它还剩两个用处：整形全军覆没（整窗都是停用词）时的退路，以及"不许比现状更宽"的基准线。
     */
    private static String legacy(String phrase) {
        if (phrase == null) return "";
        String[] parts = phrase.trim().split("\\s+");
        ArrayList<String> terms = new ArrayList<String>();
        for (int i = 0; i < parts.length; i++) {
            String term = parts[i].trim();
            if (term.length() >= 2) terms.add(term);
        }
        if (terms.size() <= MAX_TERMS) return join(terms);
        /* 取最长的两个，同样长取先出现的；输出仍按原顺序，别把服务端眼里的查询换了个次序。 */
        int best = 0, second = -1;
        for (int i = 1; i < terms.size(); i++) {
            if (terms.get(i).length() > terms.get(best).length()) { second = best; best = i; }
            else if (second < 0 || terms.get(i).length() > terms.get(second).length()) second = i;
        }
        ArrayList<String> picked = new ArrayList<String>();
        picked.add(terms.get(Math.min(best, second)));
        picked.add(terms.get(Math.max(best, second)));
        return join(picked);
    }

    /** minSdk 23 上用不了 String.join。 */
    private static String join(ArrayList<String> parts) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) out.append(' ');
            out.append(parts.get(i));
        }
        return out.toString();
    }

    /**
     * 检索表单。中文一律百分号编码，这一步不能省：命令行和部分 HTTP 客户端会把裸中文按本地码发出去，
     * 服务端回一段四个问号的错误，看着像被拒绝，其实只是编码坏了。页码夹在 1 到 50 之间。
     */
    static String form(String phrase, int page) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("Content=").append(encode(narrow(phrase)));
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

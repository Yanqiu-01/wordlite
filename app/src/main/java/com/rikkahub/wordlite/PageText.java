package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 手机渲染取回那一页之后，所有判断都在这儿：清洗、认墙、挑出该入库的那一段、给它定档。
 * 纯函数，一行 Android 都不碰——所以这一份能在主机上跑回归，真机那一步只负责把 innerText 交回来。
 *
 * 为什么判档要保守：手机里跑的是 WebView，拿到的最多是"网站愿意公开的那一段"。万方渲染后整页
 * 3,331 汉字，其中 1,500+ 字是网站自己从整篇里摘的「全文精要」；知网 wap 详情渲染后 990 汉字，
 * 正文那一节写着"下载PDF版"。这两个数都不许读成"整篇正文到手了"，所以本页给的档只有三种：
 * 精要 / 摘要 / 真给整篇 HTML 全文的站（门槛写在 MIN_BODY_CHINESE_CHARS 上，量得很高）。
 */
public final class PageText {
    private PageText() { }

    /**
     * 详情页对 UA 是敏感的：手机 UA 会拿到 wap 那一份更薄的面包屑页。
     * 这一串是桌面 Chrome 的形状，与本机 FlareSolverr 那一轮用的出口一致（docs/endpoints-access.md）。
     */
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /**
     * 静默窗口：Vue 那一类页面在 onPageFinished 之后还要等前端把数据请求回来才写 DOM，
     * 本机 Chrome --dump-dom 就因此只拿到 457 汉字（虚拟时间预算不够先交卷）。
     * 所以等"文本连续这么久不再变化"，而不是等加载完成事件。
     */
    public static final int DEFAULT_SILENCE_MILLIS = 1600;
    public static final int DEFAULT_POLL_MILLIS = 400;
    /** 一整页的上限：超过这个时间还没静默下来就按失败收，别把用户的流量和等待时间耗光。 */
    public static final int DEFAULT_TIMEOUT_MILLIS = 45000;
    /**
     * 少于这个汉字数不值得入库。万方裸 HTTP 拿回来的那个 SPA 壳正好落在这一档下面（457 个可见字符里
     * 没几句人话），拿它入库等于往语料里塞导航条与"数据加载中"。
     */
    public static final int MIN_CHINESE_CHARS = 260;
    /** 精要那一段至少要这么多个汉字才算一段话，短过这个数多半是截断后剩下的半句。 */
    public static final int MIN_DIGEST_CHINESE_CHARS = 200;
    /** 摘要那一档的下限：知网 wap 给的是一份 128 字的截断摘要，比这再短就不如说没拿到。 */
    public static final int MIN_ABSTRACT_CHINESE_CHARS = 90;
    /**
     * 渲染页想按正文级入库的门槛：整页汉字数必须高到这个数，万方（3,331）与知网 wap（990）都够不着，
     * 只有真把整篇 HTML 摊在页面上的期刊官网才够。宁可漏一档，不可把精要写成正文。
     */
    public static final int MIN_BODY_CHINESE_CHARS = 6000;

    /** 这一页是什么形状。失败种类与成功档位共用这一组名字，界面上不许各起一名。 */
    public static final String SHAPE_READABLE = "可读";
    public static final String SHAPE_LOGIN = "要登录（跳到了登录页）";
    public static final String SHAPE_CAPTCHA = "滑块/验证码";
    public static final String SHAPE_PURCHASE = "要购买（单篇付费页）";
    public static final String SHAPE_SHELL = "空壳（页面没渲染出可读汉字）";
    public static final String SHAPE_THIN = "太薄（可读汉字不够入库）";

    /** 挑出来的结果：要入库的那一段文字 + 它的档位，或者失败种类 + 一句人话。 */
    public static final class Picked {
        public boolean ok;
        public String text = "";
        /** DuplicateEngine.MATERIAL_* 之一；失败时是空串。 */
        public String material = "";
        public String shape = SHAPE_READABLE;
        public String note = "";
        public int chars;
        /** 取回那一段（精要/摘要/整页）的汉字数。 */
        public int chinese;
        /** 整页清洗后的汉字数。chinese 是一段，pageChinese 是一页，混过一次就会在注记里说谎。 */
        public int pageChinese;
        /** 落库文件名（一篇一个文件，档位写在名字里，来源榜点得出是哪一篇、哪一档）。 */
        public String name = "";

        public String describe() {
            return ok ? material + " " + chinese + " 个汉字（整页 " + chars + " 字）"
                    : shape + "：" + note;
        }
    }

    /** 汉字数：这一档材料的"厚度"用它量最稳，拉丁字母与标点撑不出正文。 */
    public static int chineseChars(String text) {
        int total = 0;
        String value = text == null ? "" : text;
        for (int i = 0; i < value.length(); i++) if (isHan(value.charAt(i))) total++;
        return total;
    }

    private static boolean isHan(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    /** 页面导航条、Cookie 提示、版权行这些外壳文本：整行短、且撞上这些词，就不该进可比文本。 */
    private static final String[] CHROME_LINES = {
        "登录", "注册", "扫码", "下载PDF版", "下载 PDF", "下载中心", "查看更多", "展开全部", "展开", "收起",
        "分享", "收藏", "引用", "举报", "返回顶部", "客服", "帮助", "意见反馈", "站内搜索",
        "Cookie", "隐私政策", "用户协议", "免责声明", "版权声明", "违法和不良信息举报", "网络文化经营许可证",
        "ICP备", "互联网出版许可证", "版权所有", "Copyright", "All rights reserved", "数据加载中", "加载中",
    };

    /**
     * 清洗：换行统一、零宽字符去掉、行尾空白去掉、三个以上空行压成一空，再丢掉那些整行都是
     * 网站外壳的行。只做行的取舍，一个字的正文内容都不改写——正文要原样进语料，判据数的是字面。
     */
    public static String clean(String raw) {
        String value = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        value = value.replace("\u00a0", " ").replace("\u200b", "").replace("\u200c", "")
                .replace("\u200d", "").replace("\ufeff", "");
        String[] lines = value.split("\n", -1);
        ArrayList<String> kept = new ArrayList<String>();
        int blank = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) {
                blank++;
                if (blank >= 2) continue;
                kept.add("");
                continue;
            }
            blank = 0;
            if (isChromeLine(line)) continue;
            kept.add(line);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < kept.size(); i++) {
            if (i > 0) out.append('\n');
            out.append(kept.get(i));
        }
        return out.toString().trim();
    }

    /**
     * 短行 + 撞上外壳词 = 网站自己的导航/版权行。长行里出现"登录"多半是被引的正文，不能删。
     * 含墙字样的行也不许删：登录页上"账号登录 短信登录"这种短行既像导航又是证据，
     * 删掉之后 shapeOf 就只能看着一堆空白说"这一页可读"，那一页差点被当正文入库。
     */
    private static boolean isChromeLine(String line) {
        if (line.length() > 24) return false;
        if (wallOf("", line).length() > 0) return false;
        String lower = line.toLowerCase(Locale.ROOT);
        for (int i = 0; i < CHROME_LINES.length; i++)
            if (lower.contains(CHROME_LINES[i].toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** 跳到了登录/验证/付费页：网址里的形状比页面文本更早说清楚这件事。 */
    private static final String[] URL_LOGIN = { "login", "sso", "passport", "signin", "logon", "account" };
    private static final String[] URL_CAPTCHA = { "captcha", "verify", "challenge", "secverify" };
    // 这一组必须按"词"匹配：/display、/displays 这类网址里也藏着 pay，整站会被误判成付费墙。
    private static final String[] URL_PURCHASE = { "purchase", "pay?", "pay/", "/pay", "order?", "order/",
            "/order", "orderlist", "cart?", "/cart", "buy?", "/buy", "checkout" };
    private static final String[] TEXT_LOGIN = { "请输入用户名", "请输入账号", "扫码登录", "请先登录",
            "账号登录", "统一认证", "机构登录", "请登录", "登录后查看", "您的账号" };
    private static final String[] TEXT_CAPTCHA = { "滑块", "安全验证", "拖动验证", "验证码", "人机验证" };
    private static final String[] TEXT_PURCHASE = { "立即购买", "加入购物车", "单篇购买", "购买全文",
            "定价：", "支付后", "文献传递" };

    /**
     * 这一页是墙还是内容。墙的形状有三种（登录 / 滑块 / 付费），认出来就算失败，
     * 而且不许把那一页的文本入库——登录页上也有"摘要""关键词"这些字样，混进语料就是脏数据。
     */
    public static String shapeOf(String finalUrl, String cleanedText) {
        String wall = wallOf(finalUrl, cleanedText);
        if (wall.length() > 0) return wall;
        if (chineseChars(cleanedText) < MIN_CHINESE_CHARS) return SHAPE_SHELL;
        return SHAPE_READABLE;
    }

    /** 只认墙、不看厚薄：网址与页面文本任一处撞上就算。没有墙返回空串。 */
    private static String wallOf(String finalUrl, String text) {
        String url = (finalUrl == null ? "" : finalUrl).toLowerCase(Locale.ROOT);
        String body = text == null ? "" : text;
        if (containsAny(url, URL_CAPTCHA)) return SHAPE_CAPTCHA;
        if (containsAny(url, URL_LOGIN)) return SHAPE_LOGIN;
        if (containsAny(url, URL_PURCHASE)) return SHAPE_PURCHASE;
        if (containsAny(body, TEXT_CAPTCHA)) return SHAPE_CAPTCHA;
        if (containsAny(body, TEXT_LOGIN)) return SHAPE_LOGIN;
        if (containsAny(body, TEXT_PURCHASE)) return SHAPE_PURCHASE;
        return "";
    }

    private static boolean containsAny(String text, String[] needles) {
        for (int i = 0; i < needles.length; i++) if (text.indexOf(needles[i]) >= 0) return true;
        return false;
    }

    /** 万方把网站自己摘的那一段标题写成"全文精要"；其他站的同一种东西用这几个词。 */
    private static final String[] DIGEST_MARKS = { "全文精要", "精要", "内容精要", "AI摘要", "AI 摘要" };
    /** 段的结束：下一节的标题。精要后面紧跟的就是参考文献或相似文献那一类。 */
    // 「全文精要」与 Abstract 也是小节标题：中文摘要那一段必须在它们前面停下，
    // 不然摘要会把精要整段吞进去，两个档的字数就分不开了。
    private static final String[] SECTION_MARKS = { "参考文献", "全文阅读", "相似文献", "相关文献",
            "作者简介", "作者介绍", "基金信息", "关键词", "分类号", "图表", "延伸阅读", "本文献已出版",
            "全文精要", "精要", "Abstract", "ABSTRACT" };
    private static final String[] ABSTRACT_MARKS = { "摘要", "Abstract", "ABSTRACT", "提要" };

    /** 标题与内容同一行时，认作内容的两个凭据：标签后面有冒号那一类断点，或者剩下的长到像一段话。 */
    private static final int SAME_LINE_MIN_CHARS = 40;

    private static boolean labelBreak(char c) {
        return c == '：' || c == ':' || c == '—' || c == '－' || c == '-';
    }

    /** 从某个标题词之后取到下一个小节标题之前；取不到返回空串。 */
    private static String sectionOf(String text, String[] marks, int minChars) {
        String[] lines = (text == null ? "" : text).split("\n", -1);
        int start = -1;
        String inline = "";
        for (int i = 0; i < lines.length && start < 0; i++) {
            String line = lines[i];
            for (int m = 0; m < marks.length; m++) {
                String mark = marks[m];
                if (line.startsWith(mark)) {
                    String rest = line.substring(mark.length());
                    String trimmed = rest.trim();
                    if (trimmed.length() == 0) {
                        start = i + 1;                      // 标题独占一行，内容在下面
                    } else if (labelBreak(trimmed.charAt(0))
                            || trimmed.length() >= SAME_LINE_MIN_CHARS) {
                        start = i + 1;                      // "摘要：……"那种同一行写法
                        inline = stripLabel(rest);
                    } else {
                        // 正文句子正好以这两个字开头（"摘要部分给出了研究方法。"），后面既没有
                        // 标签标点也没长到像一段内容，不算标题行。
                        continue;
                    }
                    break;
                }
                if (line.length() <= 30 && line.indexOf(mark) >= 0) {
                    start = i + 1;                          // 短标题行里夹了别的字（"中文摘要 (中文)"）
                    break;
                }
            }
        }
        if (start < 0) return "";
        StringBuilder out = new StringBuilder();
        if (inline.length() > 0) out.append(inline);
        for (int i = start; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() <= 30) {
                boolean next = false;
                for (int m = 0; m < SECTION_MARKS.length; m++)
                    if (line.indexOf(SECTION_MARKS[m]) >= 0) next = true;
                if (next) break;
            }
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        String section = out.toString().trim();
        return chineseChars(section) < minChars ? "" : section;
    }

    /** 标题后面那截的前导标点与空白（"："、"："、"— "），去掉它们才是能进语料的那段话。 */
    private static String stripLabel(String rest) {
        String value = rest == null ? "" : rest.trim();
        int i = 0;
        while (i < value.length() && "：:—－- \u3000".indexOf(value.charAt(i)) >= 0) i++;
        return value.substring(i).trim();
    }

    /** 「全文精要」那一段。 */
    public static String digestOf(String cleanedText) {
        return sectionOf(cleanedText, DIGEST_MARKS, MIN_DIGEST_CHINESE_CHARS);
    }

    /** 摘要那一段。 */
    public static String abstractOf(String cleanedText) {
        return sectionOf(cleanedText, ABSTRACT_MARKS, MIN_ABSTRACT_CHINESE_CHARS);
    }

    /** 退路那一段的停止线：这些表头一出现，前面那段就不再是摘要了。 */
    private static final String[] LEAD_STOP_MARKS = { "机　构", "机构:", "机构：", "机 构", "领　域",
            "领域:", "领域：", "关键词", "参考文献", "作者简介", "基金", "目　次", "目次" };

    /**
     * 没有"摘要"标题那一行的站怎么办（手机知网 wap 详情页就是这一种：摘要直接摊在题名下面，
     * 下面紧跟"机　构:"、"领　域:"、"关键词:"，整页没有一个"摘要"字样）。
     * 退一步取第一个小节表头之前最长的那一段——那种页面上唯一的长段落就是摘要，而且它多半还带着
     * 网站自己截的省略号，本来就只配算摘要档。
     */
    public static String leadParagraph(String cleanedText) {
        String[] lines = (cleanedText == null ? "" : cleanedText).split("\n", -1);
        String best = "";
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() <= 30 && containsAny(line, LEAD_STOP_MARKS)) break;
            if (line.length() < 40) continue;
            if (chineseChars(line) < MIN_ABSTRACT_CHINESE_CHARS) continue;
            if (chineseChars(line) > chineseChars(best)) best = line;
        }
        return chineseChars(best) < MIN_ABSTRACT_CHINESE_CHARS ? "" : best;
    }

    /**
     * 这一页该入库的是哪一段、算哪一档。顺序是整页够厚 → 精要 → 摘要：
     * 精要排在最前，是因为万方那种整页里既有摘要又有精要，精要厚得多，拿精要比拿摘要多。
     * 整页够厚那一档要求 MIN_BODY_CHINESE_CHARS 个汉字，够不着就不许自称正文。
     */
    public static Picked pick(String detailUrl, String finalUrl, String title, String innerText) {
        Picked picked = new Picked();
        String cleaned = clean(innerText);
        picked.chars = cleaned.length();
        picked.chinese = chineseChars(cleaned);
        // 整页的汉字数要先留住：下面挑出某一段之后 picked.chinese 会变成那一段的字数，
        // 注记与结果页那句"整页多少汉字"要是那时候再取，就会把一段的字数说成一页的字数。
        picked.pageChinese = picked.chinese;
        // 清洗会把含"登录"那两个字的短行整行删掉，所以墙先拿未清洗的原文认一遍，再拿清洗后的认一遍。
        String wall = wallOf(finalUrl, innerText);
        picked.shape = wall.length() > 0 ? wall : shapeOf(finalUrl, cleaned);
        picked.name = importName(title, detailUrl, "");
        if (!SHAPE_READABLE.equals(picked.shape)) {
            picked.note = "这一页只取回 " + picked.chinese + " 个汉字，且形状是" + picked.shape;
            return picked;
        }
        // 整页够厚排在精要之前：够得着 MIN_BODY_CHINESE_CHARS 这个门槛的页面摊开了整篇，
        // 这时候页面上哪怕有个「全文精要」标题，也只该按正文收整页——只取那一段是把四分之三材料丢掉。
        if (picked.chinese >= MIN_BODY_CHINESE_CHARS) {
            picked.ok = true;
            picked.material = DuplicateEngine.MATERIAL_FULL;
            picked.text = cleaned;
            picked.note = "整页摊开了 " + picked.chinese + " 个汉字，按正文入库";
            picked.name = importName(title, detailUrl, picked.material);
            return picked;
        }
        String digest = digestOf(cleaned);
        if (digest.length() > 0) {
            picked.ok = true;
            picked.material = DuplicateEngine.MATERIAL_DIGEST;
            picked.text = digest;
            picked.chinese = chineseChars(digest);
            picked.note = "取的是页面里「全文精要」那一段（那一段 " + picked.chinese
                    + " 个汉字，整页 " + picked.pageChinese
                    + " 个汉字），网站从整篇里自己摘的，不是整篇正文";
            picked.name = importName(title, detailUrl, picked.material);
            return picked;
        }
        String abstractText = abstractOf(cleaned);
        String how = "摘要那一段";
        if (abstractText.length() == 0) {
            abstractText = leadParagraph(cleaned);      // 那一页根本没有"摘要"这个标题，走退路
            how = "小节表头之前那一段长文（那一页没有摘要标题）";
        }
        if (abstractText.length() > 0) {
            picked.ok = true;
            picked.material = DuplicateEngine.MATERIAL_ABSTRACT;
            picked.text = abstractText;
            picked.chinese = chineseChars(abstractText);
            picked.note = "取的是" + how + "（那一段 " + picked.chinese
                    + " 个汉字，整页 " + picked.pageChinese + " 个汉字），按摘要入库；正文那一节没公开";
            picked.name = importName(title, detailUrl, picked.material);
            return picked;
        }
        picked.shape = SHAPE_THIN;
        picked.note = "认不出摘要或精要那一段，整页可读汉字 " + picked.chinese + " 个";
        return picked;
    }

    /**
     * 落库的文件名：题名 + 站点 + 档位，一篇一个文件。档位必须写在名字里——来源榜那一行的题名
     * 就是它（LocalLibrary.sourceOf 用文件名去扩展名当题名），名字里没档位就等于把档丢了。
     * 站点的键也放进去，同一篇从知网和万方各渲染一次会落成两个文件，正文哈希相同的那条会被去重挡掉。
     */
    public static String importName(String title, String url, String material) {
        StringBuilder out = new StringBuilder();
        String name = title == null ? "" : title.trim();
        out.append(name.length() == 0 ? "渲染页" : name);
        String host = hostOf(url);
        if (host.length() > 0) out.append('·').append(host);
        if (material != null && material.length() > 0) out.append('·').append(material);
        out.append(".txt");
        return out.toString();
    }

    /** d.wanfangdata.com.cn → wanfang；kns.cnki.net → cnki；认不出的主机名原样留下（去掉点，文件名里不要点）。 */
    public static String hostOf(String url) {
        String value = url == null ? "" : url.trim();
        int scheme = value.indexOf("://");
        if (scheme >= 0) value = value.substring(scheme + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int at = value.indexOf('@');
        if (at >= 0) value = value.substring(at + 1);
        int colon = value.indexOf(':');
        if (colon >= 0) value = value.substring(0, colon);
        value = value.toLowerCase(Locale.ROOT);
        if (value.indexOf("wanfang") >= 0) return "wanfang";
        if (value.indexOf("cnki") >= 0) return "cnki";
        if (value.indexOf("cqvip") >= 0 || value.indexOf("weipu") >= 0) return "cqvip";
        if (value.indexOf("ncpssd") >= 0) return "ncpssd";
        return value.replace(".", "-");
    }
}
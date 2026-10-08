package com.rikkahub.wordlite;

import java.io.File;
import java.util.ArrayList;

/**
 * 手机渲染取回一页之后的那套判断：清洗掉了什么、认出的是哪一堵墙、挑出来该入库的那一段算哪一档、
 * 落库文件名还留不留得住档位。全是纯函数与本地文件——不联网、不碰 WebView。
 * 真机那一步（在手机上渲染真地址取 innerText）由 tests/PageRenderProbe 量，
 * 命令与实测数字写在 docs/endpoints-access.md。
 *
 * 夹具形状照实测过的页面手抄（数字见 docs/endpoints-access.md）：万方详情页渲染后整页 3,331 汉字，
 * 其中 1,500 上下是网站自己从整篇里摘的「全文精要」；知网 wap 详情渲染后 990 汉字，
 * 正文那一节写着"下载PDF版"；万方那个单页应用壳裸 HTTP 拿回来只有 457 个可见字符、没几句人话。
 */
public final class PageTextRegression {
    private static int checks;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }

    private static void same(String actual, String expected, String message) {
        check(expected.equals(actual), message + "，got [" + actual + "]");
    }

    private static void same(int actual, int expected, String message) {
        check(actual == expected, message + "，got " + actual);
    }

    // ---- 夹具：三页真形状 ----

    private static final String WF_URL = "https://d.wanfangdata.com.cn/periodical/jxfcwx202419001";
    private static final String WF_TITLE = "液氮冷却切削钛铝合金的材料去除机理与温度场仿真";
    private static final String CNKI_URL = "https://wap.cnki.net/touch/web/Journal/Article/JME.2024.19.318";
    private static final String CNKI_TITLE = "钛铝合金低温切削加工温度的实验和仿真研究";

    private static final String[] SEEDS = {
        "液氮低温冷却下切屑形态由周期性断裂去除向周期性断裂与绝热剪切断裂复合去除转变，",
        "切削温度随切削速度上升而升高，随冷却流量增大而下降，在实验区间内近似线性关系，",
        "有限元模型给出的温度场与测温刀片实测值误差在十摄氏度以内，说明边界条件定义可行，",
        "本构方程在负一百九十六到六百摄氏度区间拟合，屈服强度随应变率上升有明显强化，",
    };

    /** 凑出大约 sentences 句、每句四十个汉字上下的真话形状，字数按需要堆。 */
    private static String prose(int sentences) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sentences; i++) {
            out.append(SEEDS[i % SEEDS.length]);
            if (i % 4 == 3) out.append('\n');
        }
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
        return out.toString();
    }

    private static final String WF_ABSTRACT = prose(4);
    private static final String WF_DIGEST = prose(40);

    /** 万方详情页渲染后的形状：整页既有摘要也有「全文精要」，后面跟着参考文献与关键词。 */
    private static final String WF_PAGE =
              "万方数据知识服务平台\n"
            + "登录 注册\n"
            + WF_TITLE + "\n"
            + "王强，李慧，周涛\n"
            + "机械工程学报，2024，60（19）：318-330\n"
            + "摘要\n"
            + WF_ABSTRACT
            + "全文精要\n"
            + WF_DIGEST
            + "参考文献\n"
            + "[1] 王强，李慧 . 钛合金低温切削温度场实测 [J]. 机械工程学报，2023，59（3）：112-119.\n"
            + "关键词\n"
            + "钛合金；液氮冷却；切削温度；有限元\n"
            + "数据加载中\n"
            + "版权所有 Copyright 2024 北京万方数据股份有限公司\n";

    /** 知网 wap 详情页：摘要是"摘要：……"同一行的写法，正文那一节只有一个"下载PDF版"按钮。 */
    private static final String CNKI_PAGE =
              "文章阅读\n"
            + "登录 注册\n"
            + CNKI_TITLE + "\n"
            + "王强; 李慧\n"
            + "摘要：" + prose(24)
            + "关键词\n"
            + "钛铝合金; 低温切削; 温度场\n"
            + "下载PDF版\n"
            + "20 页阅读需：40 元\n";

    /** 单页应用那个壳：一次 HTTP 请求拿回来的东西，可见字符四百多个，汉字没几句。 */
    private static final String SHELL_PAGE =
              "万方数据知识服务平台\n"
            + "Please enable JavaScript to continue.\n"
            + "Loading\n"
            + "window.__INITIAL_STATE__ = {\"loading\":true}\n"
            + "var e = document.createElement('script');\n"
            + "Copyright Beijing Wanfang Data Co., Ltd.\n";

    /** 被弹回的登录页：每一行短行里都含"登录"，清洗会把它们整行删掉，种类仍必须认得出。 */
    private static final String LOGIN_PAGE =
              "中国知识基础设施工程\n"
            + "账号登录 短信登录\n"
            + "扫码登录 密码登录\n"
            + "机构登录 统一认证\n"
            + "登录后查看摘要与关键词\n"
            + "请输入用户名和密码登录\n"
            + prose(9);

    private static final String CAPTCHA_PAGE =
              "系统检测到访问异常\n"
            + "请完成安全验证后继续访问\n"
            + "拖动滑块完成拼图\n"
            + prose(9);

    private static final String PAY_PAGE =
              "本文暂未开放获取\n"
            + "单篇购买 立即购买的按钮在这里\n"
            + "定价：20.00 元，支付后三十分钟内可下载\n"
            + prose(9);

    // ---- 1. 清洗 ----

    private static void cleaning() {
        same(PageText.clean(null), "", "clean 接 null 只回空串");
        same(PageText.clean("第一段\r\n第二段\r第三段"), "第一段\n第二段\n第三段",
                "换行统一成一个 LF，取回的文本里不许留 CR");
        check(PageText.clean("a\u00a0b\u200bc\ufeffd").equals("a bcd"),
                "不间断空格换成普通空格，零宽字符与 BOM 一律去掉");
        String blanks = PageText.clean("第一段\n\n\n\n\n第二段\n");
        check(blanks.indexOf("\n\n\n") < 0, "三个以上空行压成一个空行");
        String body = "切削温度随切削速度上升而升高，随冷却流量增大而下降。";
        same(PageText.clean(body), body, "正文行一个字都不改写：判据数的是字面，清洗只挑行不挑字");
        String quoted = "本文引用了登录服务器时的鉴权时延测量方法，并与缓存命中率放在一起讨论。";
        same(PageText.clean(quoted), quoted, "长行里出现「登录」两个字不许删：那多半是被引的正文");
        String shell = PageText.clean(WF_PAGE);
        check(!shell.contains("数据加载中"), "「数据加载中」那一行不进可比文本");
        check(!shell.contains("注册"), "导航里的「注册」那一行不进可比文本");
        check(shell.contains(WF_TITLE), "题名留下");
        check(shell.contains("全文精要"), "「全文精要」那个标题行本身留下（判断小节边界要用）");
        check(PageText.chineseChars("切削温度123 ,。") == 4, "汉字数只数汉字：数字、拉丁、标点不算厚度");
        check(PageText.chineseChars("𠮷𠮷") == 0,
                "平面 2 的生僻字（代理对）不计入汉字数：量厚度时偏保守，宁可少算几字，也不许把一长串符号读成正文");
        check(PageText.chineseChars(null) == 0, "汉字数接 null 回 0");
        check(PageText.USER_AGENT.indexOf("Mobile") < 0,
                "默认 UA 必须是桌面形状：手机 UA 会拿到更薄的 wap 页，实测薄一成");
        check(PageText.DEFAULT_SILENCE_MILLIS / PageText.DEFAULT_POLL_MILLIS >= 2,
                "静默窗口至少容得下两次轮询，否则每次轮询都可能刚好撞在文本还在变的那一次");
        check(PageText.DEFAULT_TIMEOUT_MILLIS > PageText.DEFAULT_SILENCE_MILLIS, "总时限大于静默窗口");
    }

    // ---- 2. 墙 ----

    private static void walls() {
        String fat = prose(10);
        same(PageText.shapeOf(WF_URL, PageText.clean(WF_PAGE)), PageText.SHAPE_READABLE,
                "万方渲染后的那一页判成可读");
        same(PageText.shapeOf("https://kns.cnki.net/kns8s/Login?LoginType=1", fat), PageText.SHAPE_LOGIN,
                "网址被换到登录页：认出来");
        same(PageText.shapeOf("https://passport.cnki.net/sso/check", fat), PageText.SHAPE_LOGIN,
                "统一认证那一跳也认出来");
        same(PageText.shapeOf("https://kns.cnki.net/secverify/x", fat), PageText.SHAPE_CAPTCHA,
                "验证那一跳从网址就认出来");
        same(PageText.shapeOf(WF_URL, PageText.clean(CAPTCHA_PAGE)), PageText.SHAPE_CAPTCHA,
                "滑块那一页从文本认出来");
        same(PageText.shapeOf(WF_URL, PageText.clean(PAY_PAGE)), PageText.SHAPE_PURCHASE,
                "单篇付费那一页从文本认出来");
        same(PageText.shapeOf("https://www.journal.org/article/display?id=1", fat), PageText.SHAPE_READABLE,
                "网址里藏着 pay 的 display 不许判成付费页：整站会被误判成一堵墙");
        same(PageText.shapeOf(WF_URL, "正在加载"), PageText.SHAPE_SHELL,
                "汉字不够入库的那一页判成空壳");
        // 这一条是本轮的真问题：清洗把含"登录"的短行整行删掉，墙的种类不能因此退化成"没渲染出东西"。
        String loginCleaned = PageText.clean(LOGIN_PAGE);
        check(PageText.chineseChars(loginCleaned) >= PageText.MIN_CHINESE_CHARS,
                "登录页清洗后仍有 " + PageText.chineseChars(loginCleaned) + " 个汉字：它不是空壳");
        same(PageText.shapeOf(CNKI_URL, loginCleaned), PageText.SHAPE_LOGIN,
                "清洗后的登录页仍判成登录墙");
        same(PageText.shapeOf(CNKI_URL, LOGIN_PAGE), PageText.SHAPE_LOGIN,
                "清洗前的原文也认得出来：pick 用的是这一份");
    }

    // ---- 3. 小节：精要与摘要 ----

    private static void sections() {
        String cleaned = PageText.clean(WF_PAGE);
        String digest = PageText.digestOf(cleaned);
        check(digest.length() > 0, "认得出「全文精要」那一段");
        check(PageText.chineseChars(digest) >= PageText.MIN_DIGEST_CHINESE_CHARS,
                "精要那一段有 " + PageText.chineseChars(digest) + " 个汉字，够一段话");
        check(!digest.startsWith("全文精要"), "取回的那一段里不带「全文精要」那个标题行");
        check(!digest.contains("参考文献"), "精要在「参考文献」那一节前面停下");
        check(!digest.contains("关键词"), "精要不把关键词那一行也算进去");
        String abstractText = PageText.abstractOf(cleaned);
        check(abstractText.length() > 0, "认得出摘要那一段");
        check(!abstractText.contains("全文精要"),
                "摘要必须在「全文精要」前面停下：不然两个档的字数就混在一个数里了");
        check(PageText.chineseChars(abstractText) < PageText.chineseChars(digest),
                "摘要比精要薄：" + PageText.chineseChars(abstractText) + " 对 "
                        + PageText.chineseChars(digest));
        // "摘要：……"同一行的写法（知网 wap 就是这一种）
        String cnki = PageText.clean(CNKI_PAGE);
        String sameLine = PageText.abstractOf(cnki);
        check(sameLine.length() > 0, "「摘要：」与内容同一行也认得出");
        check(!sameLine.startsWith("摘要"), "同一行写法取回来的是内容，不是那个标签");
        check(PageText.chineseChars(sameLine) >= PageText.MIN_ABSTRACT_CHINESE_CHARS,
                "同一行写法拿回的摘要有 " + PageText.chineseChars(sameLine) + " 个汉字");
        // 正文里正好以"摘要"开头、后面没有冒号：不许当成标题
        check(PageText.abstractOf("摘要部分给出了研究方法。\n" + prose(12)).length() == 0,
                "正文句子以「摘要」两个字开头时不当标题行");
        // 截断后剩下的半句不算一段精要
        String half = "全文精要\n" + prose(3);
        check(PageText.chineseChars(PageText.digestOf(half)) == 0,
                "不足 " + PageText.MIN_DIGEST_CHINESE_CHARS + " 个汉字的「精要」按没取到算");
        check(PageText.digestOf(null).length() == 0 && PageText.abstractOf(null).length() == 0,
                "两个取段函数接 null 回空串");
    }

    // ---- 4. 分档 ----

    private static void tiers() {
        PageText.Picked wf = PageText.pick(WF_URL, WF_URL, WF_TITLE, WF_PAGE);
        check(wf.ok, "万方那一页取到了可入库的一段：" + wf.describe());
        same(wf.material, DuplicateEngine.MATERIAL_DIGEST, "整页 3,331 汉字的万方页落精要档，不是正文档");
        same(wf.text, PageText.digestOf(PageText.clean(WF_PAGE)), "入库的就是精要那一段，一字不许多");
        same(wf.chinese, PageText.chineseChars(wf.text), "汉字数说的是取回那一段的，不是整页的");
        check(wf.note.indexOf("不是整篇正文") >= 0, "注记里写明这不是整篇正文：" + wf.note);
        // 精要那一段的字数与整页的字数必须在注记里各写各的：把 1,500 字写成整页字数，
        // 报告上就成了"这一页只有 1,500 字可读"，而整页其实有 3,331 字。
        check(wf.note.indexOf("那一段 " + wf.chinese + " 个汉字") >= 0,
                "注记里那一段的字数是真字数：" + wf.note);
        check(wf.note.indexOf("整页 " + PageText.chineseChars(PageText.clean(WF_PAGE))
                + " 个汉字") >= 0, "注记里整页的字数是真页数：" + wf.note);
        check(wf.name.indexOf("精要") >= 0, "落库文件名里带着档位：" + wf.name);
        check(!wf.material.equals(DuplicateEngine.MATERIAL_FULL),
                "万方那 1,500 字不许冒充整篇正文：" + PageText.chineseChars(PageText.clean(WF_PAGE))
                        + " 个汉字够不着 " + PageText.MIN_BODY_CHINESE_CHARS + " 的门槛");

        PageText.Picked cnki = PageText.pick(CNKI_URL, CNKI_URL, CNKI_TITLE, CNKI_PAGE);
        check(cnki.ok, "知网 wap 那一页取到了摘要：" + cnki.describe());
        same(cnki.material, DuplicateEngine.MATERIAL_ABSTRACT, "只有摘要可读的那一页落摘要档");
        check(cnki.note.indexOf("正文") >= 0, "注记说清正文那一节没公开：" + cnki.note);
        check(cnki.name.indexOf("摘要") >= 0, "落库文件名里带着档位：" + cnki.name);

        PageText.Picked shell = PageText.pick(WF_URL, WF_URL, WF_TITLE, SHELL_PAGE);
        check(!shell.ok, "单页应用那个壳按失败收，不许入库");
        same(shell.shape, PageText.SHAPE_SHELL, "失败种类是空壳");
        same(shell.text, "", "空壳那一页一个字的文本都不交回入库");
        same(shell.material, "", "空壳没有档位");
        check(shell.describe().indexOf("空壳") >= 0, "界面上那句失败话说的是空壳：" + shell.describe());

        PageText.Picked login = PageText.pick(WF_URL, "https://kns.cnki.net/kns8s/Login?Url=x",
                WF_TITLE, LOGIN_PAGE);
        check(!login.ok, "被弹回登录页按失败收");
        same(login.shape, PageText.SHAPE_LOGIN, "失败种类是登录墙，不是「没渲染出东西」那一类");
        same(login.text, "", "登录页那一段一个字都不许入库：那一页上也有摘要与关键词的字样");
        check(login.describe().indexOf("登录") >= 0, "失败话里点名是哪一堵墙：" + login.describe());

        PageText.Picked wallText = PageText.pick(WF_URL, WF_URL, WF_TITLE, LOGIN_PAGE);
        check(!wallText.ok && PageText.SHAPE_LOGIN.equals(wallText.shape),
                "网址没变、但页面是登录页：照样按登录墙失败");
        check(PageText.clean(LOGIN_PAGE).indexOf("摘要") >= 0,
                "这一页的文本里确实有「摘要」字样：它不是靠「页面没内容」被挡下的");

        PageText.Picked captcha = PageText.pick(WF_URL, WF_URL, WF_TITLE, CAPTCHA_PAGE);
        check(!captcha.ok && PageText.SHAPE_CAPTCHA.equals(captcha.shape), "滑块那一页按验证失败收");
        PageText.Picked pay = PageText.pick(WF_URL, WF_URL, WF_TITLE, PAY_PAGE);
        check(!pay.ok && PageText.SHAPE_PURCHASE.equals(pay.shape), "单篇付费那一页按付费失败收");

        // 既没有精要也没有摘要、整页又不够厚、也没有一段够长的正文：认不出来就失败，
        // 不许挑一段导航或几条书名当摘要入库。每一行都短于退路的门槛（40 字）。
        StringBuilder thin = new StringBuilder("综述\n");
        for (int i = 0; i < 26; i++) thin.append("第").append(i).append("条目录条目一行，短得不成段。\n");
        PageText.Picked none = PageText.pick(WF_URL, WF_URL, "无摘要的一篇", thin.toString());
        check(!none.ok, "认不出精要、摘要与退路那一段时按失败收");
        same(none.shape, PageText.SHAPE_THIN, "失败种类是太薄");
        check(none.note.indexOf("认不出") >= 0, "失败话说清是没认出那一段：" + none.note);
        check(PageText.leadParagraph(PageText.clean(thin.toString())).length() == 0,
                "退路也不许把一堆短行拼成一段摘要");
        // 退路的停止线：表头在前、长段在后，那段长文就不是摘要
        String afterHeading = "关键词\n" + prose(6);
        check(PageText.leadParagraph(PageText.clean(afterHeading)).length() == 0,
                "小节表头之后才出现的长段不算摘要：退路在表头那儿就停了");

        // 整页摊开整篇的期刊官网：够得着正文门槛就按正文收，哪怕页面上也有个「全文精要」标题
        String full = WF_TITLE + "\n摘要\n" + prose(4) + "全文精要\n" + prose(40)
                + "0 引言\n" + prose(70) + "1 材料与方法\n" + prose(70) + "参考文献\n" + prose(20);
        PageText.Picked body = PageText.pick(WF_URL, WF_URL, WF_TITLE, full);
        check(body.ok, "整页摊开了正文的那一页取回成功");
        same(body.material, DuplicateEngine.MATERIAL_FULL, "够得着 "
                + PageText.MIN_BODY_CHINESE_CHARS + " 个汉字那一档的按正文档入库");
        check(body.text.length() > PageText.digestOf(PageText.clean(WF_PAGE)).length() * 3,
                "整页够厚时取整页：只挑那一段精要是把材料丢掉四分之三");
        check(body.name.indexOf("全文") >= 0, "正文档的文件名也带档位：" + body.name);
    }

    // ---- 5. 站点与文件名 ----

    private static void names() {
        same(PageText.hostOf(WF_URL), "wanfang", "万方主机名归一成 wanfang");
        same(PageText.hostOf("https://kns.cnki.net/kcms2/article/abstract?v=a"), "cnki", "知网归一成 cnki");
        same(PageText.hostOf("https://qikan.cqvip.com/Qikan/Article/Detail?id=1"), "cqvip", "维普归一成 cqvip");
        same(PageText.hostOf("https://www.xsyk021.com/article/2024/1"), "www-xsyk021-com",
                "认不出的主机名原样留下但去掉点：文件名里不许有多余的点");
        same(PageText.hostOf("file:///android_asset/x.html"), "", "本地 asset 页没有站点名");
        same(PageText.hostOf(null), "", "hostOf 接 null 回空串");
        same(PageText.importName(WF_TITLE, WF_URL, DuplicateEngine.MATERIAL_DIGEST),
                WF_TITLE + "·wanfang·精要.txt", "文件名是题名、站点、档位三截");
        same(PageText.importName("  ", WF_URL, DuplicateEngine.MATERIAL_ABSTRACT),
                "渲染页·wanfang·摘要.txt", "没有题名时用一个不会撞车的占位名");
        same(PageText.importName("同名的一篇", WF_URL, ""), "同名的一篇·wanfang.txt",
                "不带档位时名字里没有档：这条不该进库，进库了也说不清是什么材料");
        String stored = LocalLibrary.sanitize(PageText.importName("A/B:C 的题名", CNKI_URL,
                DuplicateEngine.MATERIAL_ABSTRACT));
        check(stored.endsWith("·摘要.txt"), "题名里带斜杠冒号，清洗之后档位仍在名字末尾：" + stored);
        check(PageText.importName(WF_TITLE, WF_URL, DuplicateEngine.MATERIAL_DIGEST)
                        .indexOf(WF_TITLE) == 0,
                "题名排在最前：来源榜那一行的题名就是它");
    }

    // ---- 6. 端到端：渲染取回的那一段进自建库、进语料、命中记在精要档 ----

    private static void intoTheLibrary() throws Exception {
        String root = "artifacts/tests/page-text";
        deleteRecursively(new File(root));
        File dir = new File(root, "library");
        dir.mkdirs();
        LocalLibrary library = new LocalLibrary(dir);
        PageText.Picked picked = PageText.pick(WF_URL, WF_URL, WF_TITLE, WF_PAGE);
        check(picked.ok, "取回那一段（" + picked.chinese + " 个汉字）");
        ArrayList<CorpusImport.Source> sources = new ArrayList<CorpusImport.Source>();
        CorpusImport.Source source = new CorpusImport.Source(picked.name,
                picked.text.getBytes("UTF-8"));
        source.material = picked.material;
        sources.add(source);
        CorpusImport.Batch batch = CorpusImport.run(library, sources, null);
        same(batch.imported(), 1, "渲染取回的那一段进了自建库");
        String message = batch.receipts.get(0).message;
        check(message.indexOf("精要") >= 0, "回执写的是「只有精要可读」入库：" + message);
        check(batch.receipts.get(0).storedName.indexOf("精要") >= 0,
                "落库文件名里档位还在：" + batch.receipts.get(0).storedName);

        LocalLibrary reopened = new LocalLibrary(dir);
        TextCorpus corpus = new TextCorpus();
        reopened.index(corpus);
        same(corpus.sourceCount(), 1, "重开库之后那一篇还在语料里");
        same(corpus.sourceAt(0).material, DuplicateEngine.MATERIAL_DIGEST,
                "档位穿过索引回到语料：还是精要档");
        check(corpus.sourceCharsAt(0) > 0, "那一篇有可比字数：" + corpus.sourceCharsAt(0));

        String planted = firstSentence(picked.text);
        String draft = "本研究的现场部分集中在老城区排水管网改造。施工前的普查发现，管段接错与淤积同时存在，"
                + "水力模型的率定因此分成两步，第一步以旱季流量校核管段阻力系数。"
                + planted
                + "此后我们又对三条支管做了复核，结论与主段的判断一致，改造顺序按汇水面积重排即可。";
        TextCorpus.Report matched = corpus.match(draft, null);
        check(!matched.hits.isEmpty(), "抄自精要的那一句被检出，命中 " + matched.hits.size() + " 处");
        SourceLedger ledger = SourceLedger.aggregate(matched.hits, TextCorpus.normalize(draft),
                matched.comparedChars);
        check(!ledger.rows.isEmpty(), "来源榜上有那一行");
        SourceLedger.Row row = ledger.rows.get(0);
        same(row.digestChars, matched.duplicateChars, "这笔命中全记在精要档");
        same(row.fullChars, 0, "正文档一格都没记：精要不算正文命中");
        check(row.digestOnly(), "这一行整行都是只有精要可读");
        CorpusLedger inventory = CorpusLedger.aggregate(corpus);
        same(inventory.digestPapers, 1, "比对材料清单把这一篇数进精要组");
        same(inventory.fullPapers, 0, "正文档一组也没有：库里没有任何一篇冒充过整篇正文");
        String split = DuplicateEngine.materialSplitLine(0, row.digestChars, 0, matched.comparedChars,
                0, 1, 0);
        check(split.indexOf("命中不含正文级") >= 0 && split.indexOf("精要级") >= 0,
                "结果页那一行的说法：" + split);
        deleteRecursively(new File(root));
    }

    private static String firstSentence(String text) {
        int end = text.indexOf('。');
        return end > 20 ? text.substring(0, end + 1) : text;
    }

    /**
     * 真页面：手机知网 wap 详情页渲染后由手机 WebView 取回的整页文本（真机跑回来的那份原样存成
     * tests/samples/cnki-wap-innertext.txt，900 个汉字）。它整页没有一个"摘要"字样，
     * 摘要摊在题名下面、下面紧跟"机　构:"、"领　域:"、"关键词:"——按标题找摘要的那套判据在这儿必须让路。
     */
    private static void realPage() throws Exception {
        String raw = new String(java.nio.file.Files.readAllBytes(
                new File("tests/samples/cnki-wap-innertext.txt").toPath()), "UTF-8");
        String cleaned = PageText.clean(raw);
        check(PageText.chineseChars(cleaned) >= PageText.MIN_CHINESE_CHARS,
                "真页面有 " + PageText.chineseChars(cleaned) + " 个可读汉字，不是空壳");
        same(PageText.shapeOf(CNKI_URL, cleaned), PageText.SHAPE_READABLE, "真页面判成可读");
        check(PageText.digestOf(cleaned).length() == 0, "真页面上没有「全文精要」那一段");
        check(PageText.abstractOf(cleaned).length() == 0,
                "真页面整页没有一个摘要标题：按标题找摘要在这里取不到东西");
        String lead = PageText.leadParagraph(cleaned);
        check(lead.length() > 0, "退路取到了小节表头之前那段长文");
        check(lead.indexOf("合成孔径雷达") >= 0, "取回的正是那段摘要：" + head(lead, 40));
        check(!lead.contains("相似文献"), "退路在「关键词」「相似文献」那些表头前面停下");
        PageText.Picked picked = PageText.pick(CNKI_URL, CNKI_URL, CNKI_TITLE, raw);
        check(picked.ok, "真页面按摘要档入库：" + picked.describe());
        same(picked.material, DuplicateEngine.MATERIAL_ABSTRACT, "没有标题的摘要也只能算摘要档，不许再高");
        check(picked.note.indexOf("没有摘要标题") >= 0, "注记说得清这是退路取的那一段：" + picked.note);
        check(picked.text.indexOf("合成孔径雷达") >= 0, "入库的那一段就是那篇的摘要");
        int page = PageText.chineseChars(cleaned);
        check(picked.chinese < page, "取回那一段 " + picked.chinese
                + " 个汉字，少于整页 " + page + " 个汉字");
        check(picked.pageChinese == page, "整页汉字数单独记着（" + page
                + " 个），不会和取回那一段的 " + picked.chinese + " 个串成一个数");
        check(picked.note.indexOf("整页 " + page + " 个汉字") >= 0,
                "注记里的整页字数是真页面的 " + page + " 个汉字，不是那一段的 "
                        + picked.chinese + " 个：" + picked.note);

        // 真页面这一路走完入库与比对：命中的字数必须全记在摘要档
        TextCorpus corpus = new TextCorpus();
        TextCorpus.Source source = new TextCorpus.Source();
        source.id = "render:cnki";
        source.title = CNKI_TITLE;
        source.engine = "cnki";
        source.material = picked.material;
        corpus.add(source, picked.text);
        String planted = lead.substring(0, lead.indexOf('。') + 1);
        String draft = DECOY + planted + DECOY;
        TextCorpus.Report matched = corpus.match(draft, null);
        check(!matched.hits.isEmpty(), "抄自真页面摘要的那一句被检出，命中 " + matched.hits.size() + " 处");
        SourceLedger ledger = SourceLedger.aggregate(matched.hits, TextCorpus.normalize(draft),
                matched.comparedChars);
        check(!ledger.rows.isEmpty() && ledger.rows.get(0).abstractOnly(),
                "来源榜那一行整行都是只有摘要可比");
        same(ledger.abstractDuplicateChars, matched.duplicateChars, "这笔命中全记在摘要档");
        same(ledger.fullDuplicateChars, 0, "真页面这一路一个字都没记进正文档");
    }

    private static String head(String text, int max) {
        String value = text == null ? "" : text.replace('\n', ' ').trim();
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static final String DECOY = "本研究的现场部分集中在老城区排水管网改造。施工前的普查发现，"
            + "管段接错与淤积同时存在，水力模型的率定因此分成两步，第一步以旱季流量校核管段阻力系数。";

    public static void main(String[] args) throws Exception {
        cleaning();
        walls();
        sections();
        tiers();
        names();
        intoTheLibrary();
        realPage();
        System.out.println("SUMMARY " + checks
                + " 条渲染取字断言通过；WebView 真机渲染那一段见 tools/device-probe/PageRenderActivity.java"
                + "（跑法与实测数字在 docs/page-render.md）");
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (int i = 0; i < children.length; i++) deleteRecursively(children[i]);
        file.delete();
    }
}
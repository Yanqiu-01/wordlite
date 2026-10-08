package com.rikkahub.wordlite;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 三库接口能不能匿名用：逐个实测台。
 *
 * <p>存在理由：`docs/retrieval-recall.md` 记着"知网/万方/维普的正文要账号"这类结论，
 * 但那些结论是一轮一轮零散试出来的，散在注释里，没人把"检索口 / 摘要口 / 题录导出 / 详情正文页"
 * 四档在同一台机器、同一批真文献、直连与电脑代理两条出口上各量一遍。这一台就是补这一课：
 * 每个口都落到同一把尺上——**它回来的字节里有没有那篇真论文的题名 / 摘要 / 正文**。
 *
 * <p>口径（三条都是可复核的字节判据，不靠人眼看）：
 *   1. 种子先用生产代码问回来（{@link PaperSources#search}），所以"这个口没有这篇"不能拿
 *      "库里本来就没有"抵赖——同一句话同一个库里，另一个口刚刚返回过它；
 *   2. hasTitle = 返回字节里出现某个种子的完整题名；hasAbstract = 出现某个种子摘要的中段 24 字；
 *      汉字数 = 拆掉 script/style 之后的可读汉字数（正文页与壳页的分水岭就在这个数上）；
 *   3. 壳页单列：落地页的 {@code <title>} 与"登录/安全验证/请开启 JavaScript"字样一起打出来，
 *      200 与"给你看的东西"是两件事，这一列专门把它们分开。
 *
 * 不在闸门里：要联网、要真请求，一跑三十次。
 *   java com.rikkahub.wordlite.SourceEndpointProbe direct  [query]
 *   java com.rikkahub.wordlite.SourceEndpointProbe 127.0.0.1:7897 [query]
 */
public final class SourceEndpointProbe {
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final long GAP_MILLIS = 600L;
    /** 维普那份检索页带 __NUXT__ 状态，实测单页就 1MB 以上，400KB 的上限会把响应截断。 */
    private static final int MAX_BYTES = 3000000;
    private static String dumpDir;

    /** 一行待测接口。kind 分四档：search 检索 / detail 详情(摘要) / record 题录导出 / body 正文页。 */
    private static final class Target {
        final String engine, kind, label, method, url, form;
        Target(String engine, String kind, String label, String method, String url, String form) {
            this.engine = engine; this.kind = kind; this.label = label;
            this.method = method; this.url = url; this.form = form;
        }
    }

    private static final class Seed {
        String title = "", abstractText = "", locator = "", id = "";
    }

    public static void main(String[] argv) throws Exception {
        String proxyArg = argv.length > 0 ? argv[0].trim() : "direct";
        String query = argv.length > 1 ? argv[1] : "深度学习 图像分割 医学影像 综述";
        if (argv.length > 2 && !argv[2].trim().isEmpty()) dumpDir = argv[2].trim();
        java.net.Proxy via = "direct".equalsIgnoreCase(proxyArg) || proxyArg.isEmpty()
                ? null : Routes.parse(proxyArg);
        System.out.println("# 出口 " + ("direct".equalsIgnoreCase(proxyArg) ? "直连" : proxyArg)
                + " 查询式 " + query);

        PaperSources.Limits limits = new PaperSources.Limits();
        limits.perEngine = 4;
        limits.timeoutSeconds = 25;
        if (via != null) limits.proxy = proxyArg;
        LinkedHashMap<String, List<Seed>> seeds = new LinkedHashMap<String, List<Seed>>();
        for (String engine : new String[] { "cnki", "wanfang", "cqvip" }) {
            List<Seed> list = new ArrayList<Seed>();
            try {
                List<PaperSources.Candidate> found = PaperSources.search(engine, query, limits, null);
                for (PaperSources.Candidate c : found) {
                    if (c == null || c.source == null) continue;
                    Seed s = new Seed();
                    s.title = c.source.title == null ? "" : c.source.title.trim();
                    s.abstractText = c.abstractText == null ? "" : c.abstractText.trim();
                    s.locator = c.source.locator == null ? "" : c.source.locator.trim();
                    s.id = idOf(s.locator);
                    if (s.title.length() >= 6) list.add(s);
                    if (list.size() == 3) break;
                }
                System.out.println("# 种子 " + engine + " 取 " + list.size() + " 篇对账"
                        + (list.isEmpty() ? "（一篇都没问回来）" : "：" + cut(list.get(0).title, 30)));
            } catch (Exception error) {
                System.out.println("# 种子 " + engine + " 问失败了：" + error.getClass().getSimpleName());
            }
            seeds.put(engine, list);
        }

        System.out.printf(Locale.US, "%-8s %-7s %-34s %-6s %8s %6s %-6s %-6s %6s  %s%n",
                "源", "档位", "接口", "状态", "字节", "毫秒", "题名", "摘要", "汉字", "落地页/结论");
        for (Target target : targets(seeds, query)) {
            Row row = ask(target, seeds.get(target.engine), via);
            System.out.printf(Locale.US, "%-8s %-7s %-34s %-6s %8d %6d %-6s %-6s %6d  %s%n",
                    target.engine, target.kind, cut(target.label, 34), row.status, row.bytes, row.millis,
                    row.hasTitle ? "有" : "无", row.hasAbstract ? "有" : "无", row.cjk, row.note);
            Thread.sleep(GAP_MILLIS);
        }
    }

    // ---- 待测清单 ----

    private static List<Target> targets(LinkedHashMap<String, List<Seed>> seeds, String query) {
        String q = enc(query);
        List<Target> out = new ArrayList<Target>();
        String cnkiId = firstId(seeds.get("cnki"));
        String wfId = firstId(seeds.get("wanfang"));
        String cqId = firstId(seeds.get("cqvip"));

        out.add(new Target("cnki", "search", "search.cnki.com.cn listresult（现役·走生产代码）", "SKIP",
                "https://search.cnki.com.cn/search/listresult", null));
        out.add(new Target("cnki", "search", "search.cnki.com.cn Search/Result", "GET",
                "https://search.cnki.com.cn/Search/Result?content=" + q, null));
        out.add(new Target("cnki", "search", "kns8s/brief/grid（KNS 真检索 API）", "POST",
                "https://kns.cnki.net/kns8s/brief/grid",
                "boolSearch=true&platform=&resourceSub=&QueryJson={\"Platform\":\"\",\"Resource\":\"CROSSDB\",\"Class\":\"CJFQ\",\"Products\":\"\",\"Cid\":\"OPT333233\",\"SearchType\":\"SUM\",\"SearchValue\":"
                        + enc(query) + ",\"QNode\":{\"QGroup\":[]},\"Exthand\":\"\",\"KuaKuCode\":\"\"}&pageNum=1&pageSize=20&sortField=&sortType=&dstyle=listmode"));
        out.add(new Target("cnki", "search", "kns8s/defaultresult/index", "GET",
                "https://kns.cnki.net/kns8s/defaultresult/index", null));
        out.add(new Target("cnki", "search", "wap.cnki.net Article/Search", "GET",
                "https://wap.cnki.net/touch/web/Article/Search?searchType=Article&searchWord=" + q
                        + "&type=&ecode=bsearch&iscross=&mobile=", null));
        out.add(new Target("cnki", "detail", "kns 题录详情 dm/manage/display", "GET",
                "https://kns.cnki.net/dm/manage/display?dbCode=CJFD&fileName=" + enc(cnkiId), null));
        out.add(new Target("cnki", "record", "kns 题录导出 dm/manage/export", "GET",
                "https://kns.cnki.net/dm/manage/export?exportFieldType=NoteExpress&exportType=exportChoose&dbType=CJFD&fileName="
                        + enc(cnkiId), null));
        out.add(new Target("cnki", "body", "wap 文章页 Journal/Article/<id>", "GET",
                "https://wap.cnki.net/touch/web/Journal/Article/" + enc(cnkiId), null));
        out.add(new Target("cnki", "body", "kns2 摘要页 article/abstract（无 token）", "GET",
                "https://kns.cnki.net/kcms2/article/abstract?v=x&uniplatform=NZKPT&language=CHS", null));

        out.add(new Target("wanfang", "search", "SearchService/search（现役 gRPC-web·走生产代码）", "SKIP",
                "https://s.wanfangdata.com.cn/SearchService.SearchService/search", null));
        out.add(new Target("wanfang", "search", "s.wanfangdata.com.cn/paper", "GET",
                "https://s.wanfangdata.com.cn/paper?q=" + q, null));
        out.add(new Target("wanfang", "search", "www 检索页 searchList", "GET",
                "https://www.wanfangdata.com.cn/search/searchList?searchWord=" + q
                        + "&pageSize=20&page=1&searchType=common", null));
        out.add(new Target("wanfang", "detail", "d.wanfangdata.com.cn 详情页", "GET",
                "https://d.wanfangdata.com.cn/periodical/" + enc(wfId), null));
        out.add(new Target("wanfang", "record", "导出接口 study/api/rest/export", "GET",
                "https://www.wanfangdata.com.cn/study/api/rest/export?ids=" + enc(wfId) + "&type=THESIS", null));
        out.add(new Target("wanfang", "body", "d.wanfangdata 详情页里的摘要锚点", "GET",
                "https://d.wanfangdata.com.cn/periodical/" + enc(wfId) + "#abstract", null));

        out.add(new Target("cqvip", "search", "www.cqvip.com/search（现役）", "GET",
                "https://www.cqvip.com/search?k=" + q, null));
        out.add(new Target("cqvip", "search", "qikan.cqvip.com 检索页", "GET",
                "https://qikan.cqvip.com/Qikan/Search/Index?key=K%3D" + q, null));
        out.add(new Target("cqvip", "search", "apiv3.cqvip.com 前端口", "GET",
                "http://apiv3.cqvip.com/search/v1/search/mag?searchType=0&keyword=" + q + "&p=1&pageSize=10", null));
        out.add(new Target("cqvip", "detail", "文献页 www.cqvip.com/doc/<id>", "GET",
                "https://www.cqvip.com/doc/" + enc(cqId), null));
        out.add(new Target("cqvip", "detail", "文章页 Qikan/Article/Detail", "GET",
                "https://www.cqvip.com/Qikan/Article/Detail?id=" + enc(cqId), null));
        out.add(new Target("cqvip", "record", "参考文献导出 refcenter", "GET",
                "https://www.cqvip.org/refcenter/refinfo?ids=" + enc(cqId), null));
        out.add(new Target("cqvip", "body", "文内页 reading/<id>", "GET",
                "https://www.cqvip.com/reading/" + enc(cqId), null));
        return out;
    }

    // ---- 一次请求 ----

    private static final class Row {
        String status = "-", note = "", via = "";
        int bytes, cjk, millis;
        boolean hasTitle, hasAbstract;
    }

    private static Row ask(Target target, List<Seed> seeds, java.net.Proxy via) {
        Row row = new Row();
        if ("SKIP".equals(target.method)) {
            row.status = "现役";
            row.note = "生产代码这一轮从它取回 " + seeds.size() + " 篇（见种子行）；裸请求形状与服务端要的不符，不重发";
            row.hasTitle = !seeds.isEmpty();
            row.hasAbstract = !seeds.isEmpty() && seeds.get(0).abstractText.length() > 40;
            return row;
        }
        long started = System.currentTimeMillis();
        try {
            LinkedHashMap<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("User-Agent", UA);
            headers.put("Accept-Language", "zh-CN,zh;q=0.9");
            headers.put("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
            ApiClient.Response response = "POST".equals(target.method)
                    ? HttpTransport.post(target.url, target.form, headers, 25, MAX_BYTES, null, via)
                    : HttpTransport.get(target.url, headers, 25, MAX_BYTES, null, via);
            row.status = String.valueOf(response.status);
            byte[] body = response.raw == null ? new byte[0] : response.raw;
            row.bytes = body.length;
            String text = response.body == null ? "" : response.body;
            if (text.length() == 0 && body.length > 0) text = new String(body, "UTF-8");
            if (response.via != null && response.via.length() > 0) row.via = response.via;
            String visible = squash(visible(text));
            String rawFlat = squash(text);
            for (Seed seed : seeds) {
                String title = squash(seed.title);
                if (title.length() >= 6 && (rawFlat.contains(title) || visible.contains(title))) row.hasTitle = true;
                String middle = middle(seed.abstractText, 24);
                if (middle != null && visible.contains(middle)) row.hasAbstract = true;
            }
            row.cjk = cjk(visible);
            dump(target, text);
            row.note = verdict(text, visible, response.status);
        } catch (Exception error) {
            row.status = "ERR";
            row.note = error.getClass().getSimpleName()
                    + (error.getMessage() == null || error.getMessage().trim().length() == 0
                    ? "" : " " + cut(error.getMessage().trim(), 40));
        }
        row.millis = (int) (System.currentTimeMillis() - started);
        return row;
    }

    /** 落地页到底是什么：优先给 {@code <title>}，再把登录/反爬的字样带上。 */
    private static String verdict(String raw, String visible, int code) {
        StringBuilder out = new StringBuilder();
        String title = tag(raw, "title");
        if (title.length() > 0) out.append('<').append(cut(title, 26)).append('>');
        String shell = shellOf(raw);
        if (shell.length() > 0) out.append(out.length() > 0 ? " " : "").append(shell);
        if (code == 200 && out.length() == 0) out.append("200 无壳字样");
        return out.length() == 0 ? "空响应" : out.toString();
    }

    private static String shellOf(String raw) {
        String[] marks = { "安全验证", "滑动验证", "请开启JavaScript", "请打开javascript", "请输入验证码",
                "统一登录", "机构登录", "登录后查看", "购买", "IP 已退出", "Access Denied", "服务器错误" };
        for (String mark : marks) if (raw.contains(mark)) return "壳:" + mark;
        if (raw.contains("$_ts") || raw.contains("acw_sc") || raw.contains("waf")) return "壳:JS 挑战";
        if (raw.contains("请登录") || raw.contains("请先登录")) return "壳:要登录";
        return "";
    }

    private static String tag(String raw, String name) {
        int at = indexOfTag(raw, name);
        if (at < 0) return "";
        int end = raw.toLowerCase(Locale.US).indexOf("</" + name, at);
        if (end < 0) return "";
        return raw.substring(at, end).replaceAll("\\s+", " ").trim();
    }

    private static int indexOfTag(String raw, String name) {
        int best = -1;
        int from = 0;
        String lower = raw.toLowerCase(Locale.US);
        while (true) {
            int at = lower.indexOf("<" + name, from);
            if (at < 0) break;
            int close = raw.indexOf('>', at);
            if (close > 0 && raw.charAt(at + name.length() + 1) == '>') return at + name.length() + 1;
            if (close < 0) break;
            from = close;
            if (best < 0) best = at;
        }
        return -1;
    }

    /** 拆掉 script/style 再拆标签：壳页拆完就没几个字，正文页拆完还剩几千字。 */
    static String visible(String raw) {
        String text = raw == null ? "" : raw;
        text = text.replaceAll("(?is)<script.*?</script>", " ");
        text = text.replaceAll("(?is)<style.*?</style>", " ");
        text = text.replaceAll("(?is)<noscript.*?</noscript>", " ");
        text = text.replaceAll("(?is)<template.*?</template>", " ");
        text = text.replaceAll("(?is)<svg.*?</svg>", " ");
        text = text.replaceAll("(?is)<!--.*?-->", " ");
        text = text.replaceAll("(?s)<[^>]+>", " ");
        return text.replace("&nbsp;", " ").replace("&#160;", " ").replace("&amp;", "&");
    }

    private static int cjk(String text) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) >= 0x4E00 && text.charAt(i) <= 0x9FFF) total++;
        return total;
    }

    private static String squash(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    /** 响应原文留一份：壳页与正文页的差别要能翻着看，不能只信这台打出来的那一列。 */
    private static void dump(Target target, String text) {
        if (dumpDir == null || text.length() == 0) return;
        try {
            String name = target.engine + "-" + target.kind + "-"
                    + target.label.replaceAll("[^0-9A-Za-z]+", "-").replaceAll("-+", "-") + ".html";
            java.io.File file = new java.io.File(dumpDir, name);
            file.getParentFile().mkdirs();
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            out.write(text.getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
            // 转储只是留证据，写不下去不许影响实测本身
        }
    }

    private static String middle(String text, int want) {
        String clean = squash(text);
        if (clean.length() < want + 8) return null;
        int from = Math.max(0, (clean.length() - want) / 2);
        return clean.substring(from, from + want);
    }

    private static String idOf(String locator) {
        int slash = Math.max(locator.lastIndexOf('/'), locator.lastIndexOf('='));
        String tail = slash >= 0 ? locator.substring(slash + 1) : locator;
        int query = tail.indexOf('?');
        return query > 0 ? tail.substring(0, query) : tail;
    }

    private static String firstId(List<Seed> seeds) {
        if (seeds == null) return "unknown";
        for (Seed seed : seeds) if (seed.id.length() > 2 && !seed.id.equals(seed.locator)) return seed.id;
        for (Seed seed : seeds) if (seed.id.length() > 2) return seed.id;
        return "unknown";
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception error) {
            return "";
        }
    }

    private static String cut(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
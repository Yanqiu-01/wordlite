package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Built-in scan engines, per-engine depth and the optional CORE key. */
public final class EngineSettings {
    /** Engines that need no credential; CORE stays opt-in because it needs a key. */
    public static final String[] DEFAULT_ENGINES = {"cnki", "cqvip", "wanfang", "ncpssd", "openalex", "crossref", "semantic-scholar", "europepmc", "arxiv"};
    public boolean web = true;
    public final ArrayList<String> engines = new ArrayList<String>();
    /* windows 管的是"每家检索源最多被问几扇窗口"，不是"整轮只查前几扇"：一轮问哪些扇、
       每扇问几家，由 DuplicateEngine.allocate() 在请求额度里排（先铺宽再铺深）。新装机默认从 12
       降到 6 的理由不变：实测一轮 9 个源约 10 秒（维普最慢 4062ms），12 轮加限速容易撞 180 秒
       挂钟闸。用户自己设过的值在 deserialize() 里原样读回，这条只改新装机。
       perEngine 只管每次请求要几条，不再是整轮配额。 */
    public int perEngine = 12, timeoutSeconds = 20, windows = 6;
    /**
     * 扫描时顺手抓正文：检索回来的候选里挂着可直下的开放获取 PDF 的那些，不再等用户到结果页点
     * "下进自建库"，同一轮里直接抓回来按正文比对。真机 2.6.4 那轮 12/49 扇、比对材料 51 篇里
     * 只有 4 篇有正文，另有 8 篇挂着能下的 PDF 躺在那儿等手指——0.13% 就是这么来的。
     * 抓取用的是检索剩下的那点请求额度（与检索抢同一个 120 次），所以 autoPdfs 有上限。
     */
    public boolean autoPdf = true;
    /** 一轮最多顺手抓几篇（0-10）。0 等于关掉。 */
    public int autoPdfs = 6;
    public String coreKey = "";
    /** Optional HTTP proxy for the built-in sources, written as host:port and empty by default:
     *  a phone on a network that resets these hosts needs one to retrieve anything at all. */
    public String proxy = "";
    /** 可选的过验证服务地址（FlareSolverr，写成 http://192.168.1.20:8191）。知网、万方、维普
     *  回人机验证壳页时由它开一个真浏览器过一道，手机只当客户端。留空 = 不走过验证这条路。 */
    public String solver = "";

    public EngineSettings() {
        for (String engine : DEFAULT_ENGINES) engines.add(engine);
    }

    public EngineSettings copy() {
        EngineSettings out = new EngineSettings();
        out.web = web;
        out.engines.clear();
        for (String engine : engines) out.engines.add(engine);
        out.perEngine = perEngine;
        out.timeoutSeconds = timeoutSeconds;
        out.windows = windows;
        out.autoPdf = autoPdf;
        out.autoPdfs = autoPdfs;
        out.coreKey = coreKey;
        out.proxy = proxy;
        out.solver = solver;
        return out;
    }

    public void validate() {
        if (perEngine < 1 || perEngine > 50 || timeoutSeconds < 5 || timeoutSeconds > 120
                || windows < 1 || windows > 24) throw new IllegalArgumentException("检索参数无效");
        if (autoPdfs < 0 || autoPdfs > 10) throw new IllegalArgumentException("顺手抓正文的篇数需为 0-10");
        if (coreKey.length() > 4096 || coreKey.indexOf('\r') >= 0 || coreKey.indexOf('\n') >= 0)
            throw new IllegalArgumentException("CORE 密钥无效");
        proxy = proxy.trim();
        if (proxy.length() > 0) {
            int colon = proxy.lastIndexOf(':');
            if (colon < 1 || colon == proxy.length() - 1 || proxy.indexOf('/') >= 0
                    || proxy.indexOf(' ') >= 0 || proxy.indexOf('@') >= 0 || proxy.indexOf("://") >= 0)
                throw new IllegalArgumentException("代理需写成 host:port");
            int port;
            try { port = Integer.parseInt(proxy.substring(colon + 1)); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("代理端口无效"); }
            if (port < 1 || port > 65535) throw new IllegalArgumentException("代理端口无效");
        }
        String wanted = solver == null ? "" : solver.trim();
        solver = ChallengeSolver.endpoint(wanted);
        if (wanted.length() > 0 && solver.length() == 0)
            throw new IllegalArgumentException("过验证地址需写成 http://host:8191");
        if (solver.length() > 300) throw new IllegalArgumentException("过验证地址过长");
    }

    public static String serialize(EngineSettings value) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("version", 5); out.put("web", value.web); out.put("engines", value.engines);
        out.put("perEngine", value.perEngine); out.put("timeout", value.timeoutSeconds);
        out.put("windows", value.windows); out.put("coreKey", value.coreKey);
        out.put("proxy", value.proxy);
        out.put("solver", value.solver);
        out.put("autoPdf", value.autoPdf);
        out.put("autoPdfs", value.autoPdfs);
        return ApiJson.stringify(out);
    }

    public static EngineSettings deserialize(String text) {
        Object root = ApiJson.parse(text);
        if (!(root instanceof Map)) throw new IllegalArgumentException("检索设置无效");
        EngineSettings out = new EngineSettings();
        out.web = !Boolean.FALSE.equals(ApiJson.path(root, "web"));
        Object list = ApiJson.path(root, "engines");
        if (list instanceof List) {
            out.engines.clear();
            for (Object item : (List<?>) list)
                if (item instanceof String && ((String) item).length() > 0) out.engines.add((String) item);
            /* 1 号格式早于后来加的检索源，那些源按"新装"处理一起打开；从 2 号格式起，用户勾掉的就是勾掉了。 */
            Object version = ApiJson.path(root, "version");
            int saved = version instanceof Number ? ((Number) version).intValue() : 1;
// 3 号格式新增了知网，而万方从一开始就漏在了默认名单之外：老设置只补这两个，
           // 其余勾选项保持用户自己勾的样子。
            if (saved < 3) for (String added : new String[] { "cnki", "wanfang" })
                if (!out.engines.contains(added)) out.engines.add(added);
            if (saved < 2)
                for (String engine : PaperSources.engines())
                    if (!out.engines.contains(engine)) out.engines.add(engine);
        }
        out.perEngine = integer(ApiJson.path(root, "perEngine"), out.perEngine);
        out.timeoutSeconds = integer(ApiJson.path(root, "timeout"), out.timeoutSeconds);
        out.windows = integer(ApiJson.path(root, "windows"), out.windows);
        Object key = ApiJson.path(root, "coreKey");
        out.coreKey = key instanceof String ? (String) key : "";
        /* 4 号格式新增顺手抓正文。老设置里没有这两项，按新装机处理：开着、最多 6 篇——
           这一条改变的是"有没有正文可比"，静默关掉等于让老用户继续看 0%。 */
        out.autoPdf = !Boolean.FALSE.equals(ApiJson.path(root, "autoPdf"));
        out.autoPdfs = integer(ApiJson.path(root, "autoPdfs"), out.autoPdfs);
        Object via = ApiJson.path(root, "proxy");
        out.proxy = via instanceof String ? ((String) via).trim() : "";
        Object helper = ApiJson.path(root, "solver");
        out.solver = helper instanceof String ? ((String) helper).trim() : "";
        return out;
    }

    private static int integer(Object value, int fallback) {
        if (!(value instanceof Number)) return fallback;
        return ((Number) value).intValue();
    }
}

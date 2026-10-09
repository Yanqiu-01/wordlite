package com.rikkahub.wordlite;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * "同样 120 次请求、同样 180 秒，这一篇的正文到底有多少字真进了比对"的测量台（host）。
 *
 * <p>它存在的理由是一条真机读数：2.6.2 在华为 CDY-AN90 上跑 tests/samples/input-liu.docx，
 * 120 次请求走到 8/49 个检索窗口就报"检索请求已达上限"，覆盖 4188/19967 字，可比正文 0 篇。
 * 那一轮的钱是怎么花掉的、换个花法多覆盖多少字，不能靠嘴说。这一台把几种花法放在同一把尺上：
 * 同一份真稿、同一份额度、同一条 allocate()（或同一条 scan 链路），只是"窗口按什么顺序排、
 * 勾了哪几家、每源上限多少"不同。
 *
 * <p>四种模式：
 *   plan     不碰网络，只算额度分配（默认，秒级）：每种花法排上几扇窗、覆盖多少字、每源问几次。
 *   stub     起九个回环桩（借 RetrievalCoverageRegression 的夹具），按 --delay= 逐源钉死响应耗时，
 *            跑一轮完整 scan，报墙钟与报告页上那几个数。改前那轮在 main 的快照里跑同一个开关。
 *   latency  真联网，一家一次，量逐源响应耗时，喂给 stub 模式当延迟表。
 *   live     真联网按出厂参数跑一轮产品链路，报报告页上那几个数。
 * 用法与开关见 tools/coverage-budget-probe.ps1。plan/stub 一个真请求都不发；latency/live 才出网。
 */
public final class CoverageBudgetProbe {
    private CoverageBudgetProbe() { }

    private static final StringBuilder log = new StringBuilder();

    private static void say(String line) {
        System.out.println(line);
        log.append(line).append('\n');
    }

    private static String flag(String[] argv, String key, String fallback) {
        for (int i = 0; i < argv.length; i++)
            if (argv[i].startsWith(key)) return argv[i].substring(key.length());
        return fallback;
    }

    private static DocxDocument load(String path) throws Exception {
        FileInputStream input = new FileInputStream(path);
        try { return DocxParser.parse(input, path); } finally { input.close(); }
    }

    private static ArrayList<String> listOf(String csv) {
        ArrayList<String> out = new ArrayList<String>();
        for (String part : csv.split(",")) if (part.trim().length() > 0) out.add(part.trim());
        return out;
    }

    public static void main(String[] argv) throws Exception {
        String doc = flag(argv, "--doc=", "tests/samples/input-liu.docx");
        String mode = flag(argv, "--mode=", "plan");
        String proxy = flag(argv, "--proxy=", "");
        String enginesCsv = flag(argv, "--engines=", "");
        String out = flag(argv, "--out=", "artifacts/tmp/coverage-budget-" + mode + ".txt");
        String label = flag(argv, "--label=", "本机");
        int windows = Integer.parseInt(flag(argv, "--windows=", "12"));
        String delay = flag(argv, "--delay=", "");
        /* 默认按应用真正勾的那几家量：EngineSettings 的默认名单（CORE 要密钥，不在默认里）。 */
        ArrayList<String> engines = enginesCsv.isEmpty()
                ? new EngineSettings().engines : listOf(enginesCsv);
        DuplicateEngine.WindowPlan plan = DuplicateEngine.windowPlan(TextSelection.all(load(doc)).text,
                Integer.MAX_VALUE);
        say("DOC " + doc + " 窗口 " + plan.groups.size() + " 扇 · 可比正文 " + plan.comparableChars
                + " 字 · 检索源 " + engines.size() + " 家(" + engines + ") · 每源上限（设置里的窗口数）"
                + windows + " · 请求额度 " + DuplicateEngine.MAX_REQUESTS + " 次 · 挂钟 "
                + DuplicateEngine.MAX_SEARCH_MILLIS / 1000L + " 秒");
        if ("plan".equals(mode)) planMode(plan, engines, windows);
        else if ("stub".equals(mode)) stubMode(doc, engines, windows, delay, label);
        else if ("latency".equals(mode)) latencyMode(engines, proxy);
        else if ("live".equals(mode)) liveMode(doc, engines, windows, proxy);
        else throw new IllegalArgumentException("mode 只认 plan|stub|latency|live");
        java.nio.file.Path target = java.nio.file.Paths.get(out).toAbsolutePath();
        if (target.getParent() != null) java.nio.file.Files.createDirectories(target.getParent());
        java.nio.file.Files.write(target, log.toString().getBytes("UTF-8"));
        System.out.println("WROTE " + target);
    }

    // ---- plan：只算额度分配，不碰网络 ----

    private static void planMode(DuplicateEngine.WindowPlan plan, ArrayList<String> engines, int windows) {
        ArrayList<Integer> probes = new ArrayList<Integer>();
        int two = 0, shortest = Integer.MAX_VALUE, longest = 0;
        ArrayList<Integer> sorted = new ArrayList<Integer>(plan.chars);
        java.util.Collections.sort(sorted);
        for (int w = 0; w < plan.members.size(); w++) {
            int n = DuplicateEngine.windowProbes(plan.members.get(w)).size();
            probes.add(Integer.valueOf(n));
            if (n > 1) two++;
            int chars = plan.chars.get(w).intValue();
            shortest = Math.min(shortest, chars);
            longest = Math.max(longest, chars);
        }
        say("DOC 一扇窗口覆盖字数 最短 " + shortest + " / 中位 " + sorted.get(sorted.size() / 2)
                + " / 最长 " + longest + "；一扇压着两种主题、要多问一条款式式的窗口 " + two + " 扇");
        say("");
        say("PLAN 排法 | 源 | 每源上限 | 额度 | 排上窗口 | 覆盖字数(覆盖率) | 提问 | 每窗深度 | 有中文主库 | 没排上 | 挡在哪");
        // 出厂那一格：手机上的设置就是 windows=12、九个源全选、额度 120。
        row(plan, probes, engines, "按字数排(出厂)", windows, DuplicateEngine.MAX_REQUESTS, true);
        row(plan, probes, engines, "正文顺序", windows, DuplicateEngine.MAX_REQUESTS, false);
        // 把额度压小，才看得见"窗口按什么顺序排"值多少字：额度绑紧时两种排法排的窗口数一样、字数不一样。
        String[] pair = { "cnki", "cqvip" };
        String[] one = { "cnki" };
        for (int cap : new int[] { 12, 6, 3 }) {
            row(plan, probes, listOf(join(pair)), "按字数排", cap, DuplicateEngine.MAX_REQUESTS, true);
            row(plan, probes, listOf(join(pair)), "正文顺序", cap, DuplicateEngine.MAX_REQUESTS, false);
        }
        for (int cap : new int[] { 12, 6 }) {
            row(plan, probes, listOf(join(one)), "按字数排", cap, DuplicateEngine.MAX_REQUESTS, true);
            row(plan, probes, listOf(join(one)), "正文顺序", cap, DuplicateEngine.MAX_REQUESTS, false);
        }
        // 额度本身压小：同一份稿子、九个源，看"每扇多问一家"与"多问几扇"换的是什么。
        for (int budget : new int[] { 120, 60, 30 }) {
            row(plan, probes, engines, "按字数排", windows, budget, true);
            row(plan, probes, engines, "正文顺序", windows, budget, false);
        }
        say("");
        say("PLAN 对照：每源上限（设置里的窗口数）从 6 拉到 24，出厂那一格多排到几扇——");
        for (int cap : new int[] { 6, 12, 18, 24 }) {
            row(plan, probes, engines, "按字数排", cap, DuplicateEngine.MAX_REQUESTS, true);
        }
    }

    private static String join(String[] parts) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append(',');
            out.append(parts[i]);
        }
        return out.toString();
    }

    /** 算一种花法并打印一行：这是 allocate() 的原样输出，没有第二套算法在旁边冒充它。 */
    private static void row(DuplicateEngine.WindowPlan plan, ArrayList<Integer> probes,
                            ArrayList<String> engines, String orderName, int cap, int budget, boolean byChars) {
        DuplicateEngine.windowsByChars = byChars;
        DuplicateEngine.Allocation a = DuplicateEngine.allocate(plan.chinese, plan.chars, probes, engines, cap, budget,
                DuplicateEngine.ORPHAN_PROBE_LAYER);
        boolean[] staffed = new boolean[plan.groups.size()];
        LinkedHashMap<String, Integer> perSource = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < a.asks.size(); i++) {
            staffed[a.asks.get(i).window] = true;
            String engine = a.asks.get(i).engine;
            Integer had = perSource.get(engine);
            perSource.put(engine, Integer.valueOf(had == null ? 1 : had.intValue() + 1));
        }
        int covered = 0;
        for (int w = 0; w < plan.groups.size(); w++) if (staffed[w]) covered += plan.chars.get(w).intValue();
        String gate = a.cappedByRequests ? "请求额度" : a.cappedByCap ? "每源上限" : "全排上了";
        say(String.format(Locale.ROOT, "PLAN %s | %d家 | %d | %d | %d/%d | %d(%d%%) | %d | %d-%d | %d | %d | %s",
                orderName, engines.size(), cap, budget, a.windowsPlanned, plan.groups.size(),
                covered, plan.comparableChars == 0 ? 0 : covered * 100 / plan.comparableChars,
                a.asks.size(), a.depthLow, a.depthHigh, a.chineseWindows, a.unstaffed, gate));
        DuplicateEngine.windowsByChars = true;
    }

    // ---- stub：九个回环桩 + 逐源延迟，跑完整一轮，量墙钟 ----

    private static void stubMode(String doc, ArrayList<String> engines, int windows, String delay,
                                 String label) throws Exception {
        long savedGap = DuplicateEngine.engineGapMillis, savedMillis = DuplicateEngine.searchMillis;
        try {
            LinkedHashMap<String, Long> delays = delays(delay, RetrievalCoverageRegression.stubPaths());
            DocxDocument document = load(doc);
            long began = System.currentTimeMillis();
            DuplicateEngine.Report report;
            try {
                RetrievalCoverageRegression.startStub();
                DuplicateEngine.engineGapMillis = DuplicateEngine.MIN_ENGINE_GAP_MILLIS;
                DuplicateEngine.searchMillis = DuplicateEngine.MAX_SEARCH_MILLIS;
                for (String key : delays.keySet())
                    RetrievalCoverageRegression.setStubDelay(key, delays.get(key).longValue());
                PaperSources.Limits limits = new PaperSources.Limits();
                limits.perEngine = 12;
                limits.windows = windows;
                limits.fullTexts = 0;
                report = DuplicateEngine.scan(TextSelection.all(document), new TextCorpus(), true,
                        engines, limits, null, null);
            } finally {
                RetrievalCoverageRegression.stopStub();
            }
            long took = System.currentTimeMillis() - began;
            say("STUB[" + label + "] 延迟表（毫秒/次）" + delays + " · 同源间隔 "
                    + DuplicateEngine.MIN_ENGINE_GAP_MILLIS + "ms");
            say("RUN[" + label + "] 窗口 " + report.windowsRetrieved + "/" + report.windowsAvailable
                    + " · 覆盖 " + report.coveredChars + "/" + report.comparableChars + " 字（"
                    + percent(report) + "）· 提问 " + asks(report) + " 次 · 墙钟 " + took + "ms · 入库 "
                    + report.candidates.size() + " 条（可比 " + report.comparableCandidates + "）");
            say("RUN[" + label + "] 每源提问 " + report.windowsAsked);
            for (int i = 0; i < report.notes.size(); i++) say("NOTE " + report.notes.get(i));
        } finally {
            DuplicateEngine.engineGapMillis = savedGap;
            DuplicateEngine.searchMillis = savedMillis;
        }
    }

    private static LinkedHashMap<String, Long> delays(String text, ArrayList<String> paths) {
        LinkedHashMap<String, Long> out = new LinkedHashMap<String, Long>();
        long flat = 0L;
        try { flat = text.isEmpty() ? 0L : Long.parseLong(text); } catch (NumberFormatException ignored) { }
        if (text.indexOf('=') >= 0) {
            for (String part : text.split(";")) {
                int at = part.indexOf('=');
                if (at <= 0) continue;
                try {
                    out.put("/" + part.substring(0, at).trim().replace("/", ""),
                            Long.valueOf(Long.parseLong(part.substring(at + 1).trim())));
                } catch (NumberFormatException ignored) { }
            }
        } else for (String path : paths) out.put(path, Long.valueOf(flat));
        return out;
    }

    // ---- latency：真联网，一家一次，量逐源响应耗时 ----

    private static void latencyMode(ArrayList<String> engines, String proxy) {
        PaperSources.Limits limits = new PaperSources.Limits();
        limits.perEngine = 5;
        limits.proxy = proxy;
        limits.timeoutSeconds = 25;
        String phrase = "瞬态液相连接 多孔铜 界面组织";
        say("LATENCY 检索式=[" + phrase + "] 每家一次，经 " + (proxy.isEmpty() ? "直连优先" : proxy));
        for (String engine : engines) {
            long began = System.currentTimeMillis();
            String line;
            try {
                ArrayList<PaperSources.Candidate> found = PaperSources.search(engine, phrase, limits, null);
                line = found.size() + " 条";
            } catch (Exception error) {
                line = "失败：" + error.getMessage();
            }
            say(String.format(Locale.ROOT, "LATENCY %-16s %6d ms  %s", engine,
                    Long.valueOf(System.currentTimeMillis() - began), line));
        }
    }

    // ---- live：真联网按出厂参数跑一轮产品链路 ----

    private static void liveMode(String doc, ArrayList<String> engines, int windows, String proxy)
            throws Exception {
        PaperSources.Limits limits = new PaperSources.Limits();
        limits.perEngine = 12;
        limits.windows = windows;
        limits.fullTexts = DuplicateEngine.MAX_FULL_TEXTS;
        limits.timeoutSeconds = 25;
        limits.proxy = proxy;
        long began = System.currentTimeMillis();
        DuplicateEngine.Report report = DuplicateEngine.scan(TextSelection.all(load(doc)), new TextCorpus(),
                true, engines, limits, null, null);
        long took = System.currentTimeMillis() - began;
        say("LIVE 窗口 " + report.windowsRetrieved + "/" + report.windowsAvailable + " · 覆盖 "
                + report.coveredChars + "/" + report.comparableChars + " 字（" + percent(report) + "）· 提问 "
                + asks(report) + " 次 · 每窗 " + report.windowDepthLow + "-" + report.windowDepthHigh
                + " 家 · 中文主库 " + report.chineseWindowsAsked + " 扇 · 墙钟 " + took + "ms");
        say("LIVE 入库 " + report.candidates.size() + " 条 · 可比 " + report.comparableCandidates
                + " 篇 · 抓到全文 " + report.fullTextCandidates + " 篇 · 只有题录 "
                + report.recordOnlyCandidates + " 篇 · 总相似度比 "
                + String.format(Locale.ROOT, "%.2f%%", report.overallRate));
        say("LIVE 每源提问 " + report.windowsAsked);
        for (int i = 0; i < report.notes.size(); i++) say("NOTE " + report.notes.get(i));
    }

    private static String percent(DuplicateEngine.Report report) {
        return report.comparableChars <= 0 ? "0.0%" : String.format(Locale.ROOT, "%.1f%%",
                report.coveredChars * 100d / report.comparableChars);
    }

    private static int asks(DuplicateEngine.Report report) {
        int total = 0;
        for (String key : report.windowsAsked.keySet()) total += report.windowsAsked.get(key).intValue();
        return total;
    }
}

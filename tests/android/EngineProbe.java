package com.rikkahub.wordlite;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Live retrieval probe for the connected Android device. It drives the production stack
 * (PaperSources -> HttpTransport -> Routes) with no Context, no Activity and no APK, so it runs
 * straight off a pushed dex:
 *
 *   adb shell CLASSPATH=/data/local/tmp/wordlite-engine-probe.dex app_process / \
 *       com.rikkahub.wordlite.EngineProbe engines "深度学习 图像分割 综述"
 *
 * Usage:  EngineProbe [engines] [query] [flags]
 *   --only=a,b,c        only these engines (default: every engine in PaperSources.defaults())
 *   --proxy=host:port   Limits.proxy, the same value the app stores for a retrieval pass
 *   --per=N             perEngine (default 5)
 *   --fetch=N           fulltext mode only: how many candidates per engine get a full-text fetch (3)
 *   --timeout=SECONDS   timeoutSeconds (default 25)
 *   --core-key=K        CORE api key, else WORDLITE_CORE_KEY
 *   --query-file=PATH   read the query from a UTF-8 file (how tools/device-probe.ps1 sends CJK)
 *   --no-tcp            skip the raw TCP pre-check on every engine host
 *
 * Mode "fulltext" answers the one question the report line "可比正文 N 篇" is built on: for the
 * candidates these engines actually hand back, does PaperSources.fullText() return body text?
 * It calls the same method the app calls (DuplicateEngine's full-text step), takes the same
 * Limits, and prints the shape HttpTransport/PdfFile recorded for every attempt, so a miss names
 * its own reason (no-fulltext-url / fetch-failed / pdf-no-text-layer / thin / blocked). It ends with
 * "COMPARABLE N of M" plus the same line in the app's words, then plants one fetched sentence back
 * through TextCorpus.match to show the material really lands in the source list.
 *
 * Two things this reports that the transport alone cannot:
 * 1. A raw TCP connect per engine host first, so "the phone has no route at all" is distinguishable
 *    from "the retrieval stack failed for a subtler reason". ICMP is blocked on this device, so
 *    ping proves nothing here; TCP 443 is the honest test.
 * 2. The route that carried the traffic. ApiClient.Response.via is per-response and
 *    PaperSources.search only hands back candidates, so this reads Routes.summary(), which records
 *    the last route that actually SUCCEEDED per host. A failed engine prints via=- with tried=N,
 *    because Routes only notes a route it used; the routes a failed engine burned are not
 *    recoverable from outside HttpTransport.
 */
public final class EngineProbe {
    /**
     * 应用那一路的整轮复现：把一篇真稿（从 docx 抽出的正文，一行一段）喂给
     * DuplicateEngine.scan，参数照 ApiWorkflow 里发起查重那一处：perEngine=12、windows=6、
     * fullTexts=6、timeout=20。报的就是屏幕上那几行——可比正文（抓到开放获取全文的篇数）、
     * "可以下进自建库"那一屏的逐篇形状，以及屏上那句汇总。
     *
     * <p>为什么能在手机上跑这一段：DuplicateEngine / TextCorpus / PaperSources / CorpusImport
     * 都没有 android 依赖，缺的只是 DocxParser，所以稿件以纯文本推进同一条 scan。
     */
    /** 探针那份"读不出正文层"的链接账放这里：与 app 的自建库分开，重跑不受装机数据影响。 */
    private static final String PROBE_LEDGER_DIR = "/data/local/tmp/wordlite-probe-library";

    private static void scanPass(String document, PaperSources.Limits limits) throws Exception {
        DocxDocument document2 = new DocxDocument();
        int index = 0;
        for (String line : document.split("\n", -1)) {
            if (line.trim().isEmpty()) continue;
            DocxDocument.ParagraphBlock block = new DocxDocument.ParagraphBlock();
            block.index = index++;
            block.text = line;
            document2.blocks.add(block);
            document2.paragraphs.add(block);
        }
        TextSelection selection = TextSelection.all(document2);
        System.out.println("SCAN 稿件=" + selection.text.length() + " 字 可比字数待报告给"
                + " 段数=" + index + " 参数 per=" + limits.perEngine + " windows=" + limits.windows
                + " fullTexts=" + limits.fullTexts + " timeout=" + limits.timeoutSeconds
                + "s proxy=" + (limits.proxy.isEmpty() ? "(none)" : limits.proxy));
        /* 已知"读不出正文层"的链接账本落在探针自己的目录里：不进 app 的自建库，
           又能跨轮有效——同一条死链接在第二轮一次请求也不该再花。 */
        if (limits.unreadable == null) {
            limits.unreadable = new LocalLibrary(new java.io.File(PROBE_LEDGER_DIR));
        }
        System.out.println("LEDGER-before 已知读不出正文层的链接 " + limits.unreadable.unreadableCount() + " 条");
        TextCorpus corpus = new TextCorpus();
        long began = System.currentTimeMillis();
        DuplicateEngine.Report report = DuplicateEngine.scan(selection, corpus, true,
                PaperSources.engines(), limits, null, new DuplicateEngine.Progress() {
                    public void step(String label, int done, int total) {
                        System.out.println("  [" + done + "/" + total + "] " + label);
                    }
                });
        System.out.println("SCAN 候选=" + report.candidates.size() + " 入库=" + report.comparableCandidates
                + " 可比正文(抓到开放获取全文)=" + report.fullTextCandidates
                + " 只有摘要=" + report.abstractOnlyCandidates
                + " 只有题录=" + report.recordOnlyCandidates
                + " 顺手抓正文=试" + report.autoPdfTried + "/成" + report.autoPdfFetched
                + "/空" + report.autoPdfFailed + "/没排上" + report.autoPdfLeft
                + "（上限 " + limits.autoFullTexts + "）"
                + " 窗口=" + report.windowsRetrieved + "/" + report.windowsAvailable
                + " 覆盖=" + report.coveredChars + "/" + report.comparableChars + " 字"
                + " 总相似度比=" + String.format(Locale.ROOT, "%.2f%%", report.overallRate)
                + " 用时=" + (System.currentTimeMillis() - began) + "ms");
        /* 屏上那两句原话：比对材料清单（CorpusLedger）与顺手抓正文（autoFetchLine）。
           自建库这一档探针里没有——它不读手机上的自建库，所以正文可比篇数比 app 少一篇属正常。 */
        System.out.println("LEDGER-after 已知读不出正文层的链接 " + limits.unreadable.unreadableCount()
                + " 条 · 本轮按这条账跳过 " + report.autoPdfSkipped + " 篇");
        CorpusLedger ledger = CorpusLedger.aggregate(corpus);
        System.out.println("屏上比对材料行: " + ledger.summaryLine());
        String auto = DuplicateEngine.autoFetchLine(report.autoPdfTried, report.autoPdfFetched,
                report.autoPdfFailed, report.autoPdfLeft, report.autoPdfReason, report.autoPdfShapes,
                report.autoPdfSkipped);
        if (!auto.isEmpty()) System.out.println("屏上顺手抓正文行: " + auto);
        /* 顺手抓正文那几次落在哪条链接上、什么形状、几毫秒：留档最后那几行就是它。
           没有这几行，"没打通 N 篇"在探针里查不下去——下载那一屏同一批链接往往是通的。 */
        System.out.println("ROUTES-after-scan " + Routes.summary());
        for (int i = Math.max(0, report.shapes.size() - 12); i < report.shapes.size(); i++) {
            PaperSources.ShapeRow row = report.shapes.get(i);
            System.out.println("  FETCH " + row.engine + "  " + row.probe + "  status=" + row.status
                    + "  bytes=" + row.bodyBytes + "  ms=" + row.millis + "  shape=" + row.shape
                    + (row.error == null || row.error.isEmpty() ? "" : "  err=" + row.error));
        }
        for (String note : report.notes) System.out.println("note: " + note);

        /* "可以下进自建库"那一屏：逐篇按 app 的取法取一遍，形状留给回执，再拼屏上那句汇总。 */
        CorpusImport.Batch batch = new CorpusImport.Batch();
        batch.total = report.downloadables.size();
        int number = 0;
        for (DuplicateEngine.Downloadable pick : report.downloadables) {
            number++;
            PaperSources.PdfFetch got = PaperSources.fetchPdf(pick.url, limits, null);
            CorpusImport.Receipt receipt = new CorpusImport.Receipt();
            receipt.number = number;
            receipt.name = clip(pick.title, 40);
            receipt.status = got.chars > 0 ? CorpusImport.Status.IMPORTED : CorpusImport.Status.FAILED;
            receipt.chars = got.chars;
            receipt.pages = got.pages;
            receipt.shape = got.chars > 0 ? "" : got.shape;
            receipt.message = got.chars > 0 ? "已入库" : PaperSources.describeFetch(got);
            batch.receipts.add(receipt);
            System.out.printf(Locale.ROOT,
                    "  DL #%-2d %-9s rank=%-2d hops=%-2d status=%-4d bytes=%-9d chars=%-6d shape=%s%n"
                            + "       %s%n       %s%n",
                    number, pick.engine, 0, got.hops, got.status, got.bytes.length, got.chars, got.shape,
                    clip(pick.title, 60), clip(pick.url, 110));
        }
        System.out.println("ROUTES-after-downloads " + Routes.summary());
        System.out.println("屏上标题行 summary(): " + batch.summary());
        for (String line : batch.detail().split("\n")) System.out.println("  回执 " + line);
        System.out.println("可比正文 " + report.fullTextCandidates + " 篇 · 下载那一屏 " + batch.total
                + " 篇里真能解出字 " + batch.imported() + " 篇");
    }

    /** Sentinel the staged CnkiSearch placeholder throws, so an excluded engine reads as SKIP. */
    private static final String EXCLUDED = "WORDLITE_PROBE_EXCLUDED";
    private static final int TCP_MILLIS = 5000;

    private EngineProbe() { }

    public static void main(String[] argv) throws Exception {
        String query = "深度学习 图像分割 综述";
        String only = "", proxy = "", coreKey = trim(System.getenv("WORDLITE_CORE_KEY")), file = "";
        int per = 5, timeout = 25, repeat = 1, fetch = 3, budget = 6, windows = 0, auto = 0;
        String doc = "";
        boolean tcp = true, tcpOnly = false;
        ArrayList<String> words = new ArrayList<String>();
        for (String arg : argv) {
            if (arg == null) continue;
            if (arg.startsWith("--only=")) only = arg.substring(7);
            else if (arg.startsWith("--proxy=")) proxy = arg.substring(8);
            else if (arg.startsWith("--per=")) per = number(arg.substring(6), per);
            else if (arg.startsWith("--fetch=")) fetch = number(arg.substring(8), fetch);
            else if (arg.startsWith("--budget=")) budget = number(arg.substring(9), budget);
            else if (arg.startsWith("--repeat=")) repeat = Math.max(1, number(arg.substring(9), repeat));
            else if (arg.startsWith("--timeout=")) timeout = number(arg.substring(10), timeout);
            else if (arg.startsWith("--core-key=")) coreKey = arg.substring(11).trim();
            else if (arg.startsWith("--query-file=")) file = arg.substring(13).trim();
            else if (arg.startsWith("--windows=")) windows = number(arg.substring(10), windows);
            /* 顺手抓正文的篇数上限：0 = 关掉这一档，>0 与手机上"检索设置"里那个数同一条口径。 */
            else if (arg.startsWith("--auto=")) auto = number(arg.substring(7), auto);
            else if (arg.startsWith("--doc=")) doc = arg.substring(6).trim();
            else if (arg.equals("--no-tcp")) tcp = false;
            else if (arg.equals("--tcp-only")) { tcp = true; tcpOnly = true; }
            else words.add(arg);
        }
        int ft = words.indexOf("fulltext");
        int sc = words.indexOf("scan");
        int at = words.indexOf("engines");
        if (at >= 0) words.remove(at);
        if (ft >= 0) words.remove(ft);
        if (sc >= 0) words.remove(sc);
        if (!words.isEmpty()) query = words.get(0);
        if (!file.isEmpty()) {
            String read = new String(Files.readAllBytes(Paths.get(file)), Charset.forName("UTF-8")).trim();
            if (!read.isEmpty()) query = read;
        }

        PaperSources.Limits limits = new PaperSources.Limits();
        limits.perEngine = per;
        limits.timeoutSeconds = timeout;
        limits.proxy = proxy.trim();
        limits.coreKey = coreKey;
        Routes.reset();

        System.out.println("probe-on-device query=" + cut(query, 48) + " chars=" + query.length()
                + " per=" + per + " timeout=" + timeout + "s proxy=" + (limits.proxy.isEmpty() ? "(none)" : limits.proxy));
        System.out.println("java=" + System.getProperty("java.vm.name", "?") + " " + System.getProperty("java.version", "?")
                + " android=" + System.getProperty("java.vm.version", "?"));
        if (windows > 0) limits.windows = windows;
        limits.autoFullTexts = Math.max(0, auto);
        if (tcp) tcpPrecheck(limits);
        if (tcpOnly) {
            System.out.println("SUMMARY tcp-only");
            return;
        }

        if (sc >= 0) {
            String body = new String(Files.readAllBytes(Paths.get(doc)), Charset.forName("UTF-8"));
            scanPass(body, limits);
            return;
        }
        if (ft >= 0) {
            ArrayList<PaperSources.Candidate> pool = fulltextPass(query, limits, wanted(only), fetch);
            queuedPass(query, limits, pool, budget, per);
            return;
        }
        Set<String> wanted = wanted(only);
        int failures = 0, skipped = 0, candidates = 0, attempted = 0;
        /* 同一个查询重复几轮：路由记忆到底有没有省下那条死直连，只有第二轮往后的耗时说得清。 */
        for (int round = 1; round <= repeat; round++) {
            long roundStarted = System.nanoTime();
            for (String engine : PaperSources.engines()) {
                if (!wanted.contains(engine)) continue;
                attempted++;
                if ("core".equals(engine) && limits.coreKey.isEmpty()) {
                    skipped++;
                    System.out.printf(Locale.ROOT, "SKIP %-17s tried=%-8s via=%s%n", engine, "-", "needs WORDLITE_CORE_KEY");
                    continue;
                }
                /* 候选队列整条列出来，不是只报个数：真机上"这条代理到底被试过没有"就是要能从
                   读数里直接看出来（2026-10-09 那条"试过的路都没通：直连 拒绝连接"坑就坑在这儿）。 */
                List<Proxy> roads = Routes.order(Routes.host(PaperSources.endpoint(engine)),
                        PaperSources.proxyFor(limits));
                int tried = roads.size();
                long started = System.nanoTime();
                try {
                    ArrayList<PaperSources.Candidate> found = PaperSources.search(engine, query, limits, null);
                    long ms = (System.nanoTime() - started) / 1000000L;
                    candidates += found.size();
                    System.out.printf(Locale.ROOT, "OK   %-17s hits=%-3d %5dms tried=%-2d via=%-22s %s%n", engine,
                            found.size(), ms, tried, routeOf(engine),
                            found.isEmpty() ? "(no hits)" : cut(found.get(0).source.title, 60));
                } catch (Exception error) {
                    long ms = (System.nanoTime() - started) / 1000000L;
                    if (EXCLUDED.equals(String.valueOf(error.getMessage()))) {
                        skipped++;
                        System.out.printf(Locale.ROOT, "SKIP %-17s tried=%-8s via=%s%n", engine, "-",
                                "not in this probe build");
                        continue;
                    }
                    failures++;
                    String status = error instanceof ApiClient.Failure
                            ? " http=" + ((ApiClient.Failure) error).status : "";
                    /* route= 就是自检那一屏最后一栏取的那个数（Routes.routeFor），这里印出来
                       是为了让那一栏能在真机上被读到，而不是只能在屏幕上抄。 */
                    System.out.printf(Locale.ROOT,
                            "FAIL %-17s %9s tried=%-2d route=%-34s roads=%s %s%s%n",
                            engine, ms + "ms", tried,
                            Routes.routeFor(Routes.host(PaperSources.endpoint(engine))),
                            roadLabels(roads), String.valueOf(error.getMessage()), status);
                }
            }
            System.out.println("ROUND " + round + " " + ((System.nanoTime() - roundStarted) / 1000000L)
                    + "ms candidates=" + candidates + " routes=" + Routes.summary());
        }
        String routes = Routes.summary();
        System.out.println("ROUTES " + (routes.isEmpty() ? "(no source was reached)" : routes));
        int ran = attempted - skipped;
        System.out.println("SUMMARY engines=" + attempted + " usable=" + (ran - failures) + " failures=" + failures
                + " skipped=" + skipped + " candidates=" + candidates);
        if (ran > 0 && failures == ran) {
            System.err.println("every attempted engine failed on this device");
            System.exit(1);
        }
    }

    /**
     * 全文这一档：每一路检索回来的候选，挨个送进 PaperSources.fullText()——app 里"抓到正文才算
     * 可比正文"用的就是这一个方法、同一份 Limits，所以这里印出来的就是报告里那一行的来历。
     * 每一次尝试都把它自己的形状打出来：没链接、连不上、扫描版没文字层、页面是 JS 壳、被挡，
     * 五种失败在界面上是五种下一步，不能都印成"没抓到"。
     */
    /** 候选队列里按先后排了哪几条路。空列表不可能：直连永远兜底。 */
    private static String roadLabels(List<Proxy> roads) {
        StringBuilder out = new StringBuilder("[");
        for (Proxy road : roads) {
            if (out.length() > 1) out.append("、");
            out.append(Routes.label(road));
        }
        return out.append(']').toString();
    }

    private static ArrayList<PaperSources.Candidate> fulltextPass(String query, PaperSources.Limits base,
                                                                  Set<String> wanted, int fetch) {
        final int minCjk = 800;
        ArrayList<PaperSources.Candidate> pool = new ArrayList<PaperSources.Candidate>();
        int tried = 0, body = 0, cjkBody = 0;
        LinkedHashMap<String, int[]> rollup = new LinkedHashMap<String, int[]>();
        TextCorpus corpus = new TextCorpus();
        for (String engine : PaperSources.engines()) {
            if (!wanted.contains(engine)) continue;
            ArrayList<PaperSources.Candidate> found;
            try {
                found = PaperSources.search(engine, query, base, null);
            } catch (Exception error) {
                System.out.printf(Locale.ROOT, "SKIP   %-11s search failed: %s%n", engine,
                        clip(String.valueOf(error.getMessage()), 88));
                continue;
            }
            pool.addAll(found);
            int take = Math.min(found.size(), fetch);
            int[] tally = new int[]{take, 0};
            System.out.printf(Locale.ROOT, "SEARCH %-11s hits=%-3d 试取 %d via=%s%n", engine, found.size(), take,
                    routeOf(engine));
            for (int i = 0; i < take; i++) {
                final PaperSources.Candidate candidate = found.get(i);
                String url = candidate.fullTextUrl == null ? "" : candidate.fullTextUrl.trim();
                String locator = candidate.source == null || candidate.source.locator == null
                        ? "" : candidate.source.locator;
                tried++;
                if (url.isEmpty()) {
                    System.out.printf(Locale.ROOT, "  NONE   %-9s 没有全文链接  locator=%s%n         %s%n",
                            engine, clip(locator, 62), clip(title(candidate), 60));
                    continue;
                }
                final PaperSources.ShapeRow[] seen = new PaperSources.ShapeRow[1];
                PaperSources.Limits one = base.copy();
                one.shapes = new PaperSources.ShapeSink() {
                    public void record(PaperSources.ShapeRow row) { seen[0] = row; }
                };
                long began = System.nanoTime();
                String text;
                try {
                    text = PaperSources.fullText(candidate, one, null);
                } catch (Exception error) {
                    text = "";
                    System.out.println("  抛异常 " + clip(String.valueOf(error), 90));
                }
                long ms = (System.nanoTime() - began) / 1000000L;
                int chars = text == null ? 0 : text.trim().length();
                int cjk = countCjk(text);
                String shape = seen[0] == null ? (chars > 0 ? "got-text" : "no-row") : seen[0].shape;
                String note = seen[0] == null ? "" : (seen[0].error.length() > 0 ? seen[0].error : seen[0].excerpt);
                if (chars > 0) {
                    body++;
                    tally[1]++;
                    if (cjk >= minCjk) cjkBody++;
                    plant(corpus, candidate, text);
                }
                System.out.printf(Locale.ROOT, "  %-6s %-9s chars=%-7d 汉字=%-6d %-17s %6dms%s%n         链接 %s%n         题 %s%n",
                        chars > 0 ? "BODY" : "EMPTY", engine, chars, cjk, shape, ms,
                        note.length() > 0 ? " " + clip(note, 60) : "", clip(url, 92), clip(title(candidate), 74));
            }
            rollup.put(engine, tally);
        }
        StringBuilder each = new StringBuilder();
        for (String engine : rollup.keySet()) {
            int[] tally = rollup.get(engine);
            if (tally[0] == 0) continue;
            each.append(' ').append(engine).append('=').append(tally[1]).append('/').append(tally[0]);
        }
        String routes = Routes.summary();
        System.out.println("ROUTES " + (routes.isEmpty() ? "(no source was reached)" : routes));
        System.out.println("COMPARABLE " + body + " of " + tried + "   逐源正文/试取" + each);
        System.out.println("可比正文 " + body + " 篇（试取 " + tried + " 条候选；其中中文正文 " + cjkBody
                + " 篇，口径是汉字 ≥ " + minCjk + "）");
        System.out.println("SELFTEST " + body + " 篇正文进了语料（语料里现有 " + corpus.sentenceCount() + " 句），见上面 SELFTEST-ONE 那几行");
        return pool;
    }

    /**
     * app 真正走的那一队，和 DuplicateEngine 第二步一模一样：候选合池 →
     * CandidateRanker.plan(检索式, 池子, 全文额度, 每源上限 perEngine) → 只抓 plan 里标了
     * fetchFullText 的那几条。上面那一节是"每源挨个试几条"，这一节才是报告里
     * "可比正文 N 篇"那一行的来历，两个数不一样是应该的：额度只有 6 次。
     */
    private static void queuedPass(String query, PaperSources.Limits base,
                                   ArrayList<PaperSources.Candidate> pool, int budget, int per) {
        ArrayList<CandidateRanker.Selection> plan = CandidateRanker.plan(query, pool, budget, Math.max(1, per));
        int got = 0, tried = 0, chars = 0, cjkBody = 0;
        System.out.println("QUEUE  池 " + pool.size() + " 条 → 计划入库 " + plan.size()
                + " 条 → 花全文额度的按 budget=" + budget + " 排（零分与必死链接不占）");
        for (CandidateRanker.Selection pick : plan) {
            if (!pick.fetchFullText) continue;
            PaperSources.Candidate candidate = pick.candidate;
            String url = candidate.fullTextUrl == null ? "" : candidate.fullTextUrl.trim();
            tried++;
            String text;
            try {
                text = PaperSources.fullText(candidate, base, null);
            } catch (Exception error) {
                text = "";
            }
            int len = text == null ? 0 : text.trim().length();
            int cjk = countCjk(text);
            if (len > 0) {
                got++;
                chars += len;
                if (cjk >= 800) cjkBody++;
            }
            System.out.printf(Locale.ROOT, "  QUEUED %-9s rank=%-3d score=%-6.3f chars=%-7d 汉字=%-6d %s%n         %s%n",
                    candidate.source == null ? "?" : candidate.source.engine, pick.rank, pick.score, len, cjk,
                    len > 0 ? "BODY" : "EMPTY", clip(url, 96));
        }
        System.out.println("ROUTES-after-queue " + Routes.summary());
        System.out.println("COMPARABLE " + got + " of " + budget + "   (attempted " + tried + ")");
        System.out.println("可比正文 " + got + " 篇 · 取回正文共 " + chars + " 字 · 其中中文正文 " + cjkBody + " 篇");
    }

    /** 把取回的正文按 app 的同一条路进语料，再抄它自己的一句话比一次：证明这批字真的可比。 */
    private static void plant(TextCorpus corpus, PaperSources.Candidate candidate, String text) {
        try {
            corpus.add(candidate.source, text);
            String sentence = firstSentence(text, 40);
            if (sentence.length() == 0) {
                System.out.println("         SELFTEST-ONE 这一段里没有够 40 个汉字的整句，不做命中自检");
                return;
            }
            String draft = "本研究的现场部分集中在老城区排水管网改造。" + sentence
                    + "施工前的普查发现，管段接错与淤积同时存在。";
            TextCorpus.Report matched = corpus.match(draft, null);
            String material = candidate.source == null ? "" : candidate.source.material;
            System.out.printf(Locale.ROOT,
                    "         SELFTEST-ONE 抄了取回正文的一句：%d 处命中 / %d 字 / 分母 %d 字，档位 [%s]%n",
                    matched.hits.size(), matched.duplicateChars, matched.comparedChars,
                    material.length() > 0 ? material : "未记档");
        } catch (Exception error) {
            System.out.println("         SELFTEST-ONE 自检没跑成：" + clip(String.valueOf(error), 80));
        }
    }

    /** 第一句够长的话：够短的句子在哪儿都能碰上，证明不了什么。 */
    private static String firstSentence(String text, int minCjk) {
        String value = text == null ? "" : text;
        int from = 0;
        while (from < value.length()) {
            int end = -1;
            for (int i = from; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '。' || c == '！' || c == '？' || c == '.') { end = i; break; }
            }
            if (end < 0) return "";
            String sentence = value.substring(from, end + 1).trim();
            if (countCjk(sentence) >= minCjk) return sentence;
            from = end + 1;
        }
        return "";
    }

    private static int countCjk(String text) {
        int n = 0;
        String value = text == null ? "" : text;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) n++;
        }
        return n;
    }

    private static String title(PaperSources.Candidate candidate) {
        return candidate == null || candidate.source == null || candidate.source.title == null
                ? "" : candidate.source.title;
    }

    private static String clip(String value, int max) {
        String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** Plain TCP connect to each engine host, so a dead phone radio is not mistaken for a code bug. */
    private static void tcpPrecheck(PaperSources.Limits limits) {
        Set<String> hosts = new LinkedHashSet<String>();
        for (String engine : PaperSources.engines()) {
            String host = Routes.host(PaperSources.endpoint(engine));
            if (!host.isEmpty()) hosts.add(host + ":443");
        }
        if (!limits.proxy.isEmpty()) hosts.add(limits.proxy + " (proxy)");
        StringBuilder out = new StringBuilder("TCP  ");
        int open = 0;
        for (String target : hosts) {
            boolean isProxy = target.endsWith(")");
            String hostPort = isProxy ? target.substring(0, target.indexOf(' ')) : target;
            int colon = hostPort.lastIndexOf(':');
            String host = hostPort.substring(0, colon);
            int port = number(hostPort.substring(colon + 1), 443);
            long started = System.nanoTime();
            Socket socket = new Socket();
            String note;
            try {
                socket.connect(new InetSocketAddress(host, port), TCP_MILLIS);
                open++;
                note = host + "=" + ((InetSocketAddress) socket.getRemoteSocketAddress()).getAddress().getHostAddress()
                        + "/" + ((System.nanoTime() - started) / 1000000L) + "ms";
            } catch (Exception error) {
                String why = String.valueOf(error); note = host + "=DOWN(" + why.substring(Math.max(0, why.length() - 76)) + ")";
            } finally {
                try { socket.close(); } catch (IOException ignored) { }
            }
            out.append(note).append(' ');
        }
        System.out.println(out + "=> " + open + " of " + hosts.size() + " open");
    }

    /** Route Routes recorded for this engine's host, or "-" when nothing succeeded there. */
    private static String routeOf(String engine) {
        String host = Routes.host(PaperSources.endpoint(engine));
        if (host.isEmpty()) return "-";
        for (String pair : Routes.summary().split("  ")) {
            int space = pair.indexOf(' ');
            if (space > 0 && pair.substring(0, space).equals(host)) return pair.substring(space + 1);
        }
        return "-";
    }

    private static Set<String> wanted(String only) {
        Set<String> out = new HashSet<String>(PaperSources.engines());
        if (only == null || only.trim().isEmpty()) return out;
        Set<String> picked = new HashSet<String>();
        for (String name : Arrays.asList(only.split(",")))
            if (!name.trim().isEmpty()) picked.add(name.trim().toLowerCase(Locale.ROOT));
        out.retainAll(picked);
        return out.isEmpty() ? new HashSet<String>(PaperSources.engines()) : out;
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }

    private static int number(String text, int fallback) {
        try { return Integer.parseInt(text.trim()); } catch (Exception ignored) { return fallback; }
    }

    private static String cut(String value, int max) {
        if (value == null || value.isEmpty()) return "(empty)";
        String flat = value.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }
}


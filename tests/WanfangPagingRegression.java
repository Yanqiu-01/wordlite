package com.rikkahub.wordlite;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * 万方取尽判据的回归：翻页游标、页间去重、连续零新增阈值三个量分开钉，谁也不许替谁说话。
 *
 * <p>它要防的是 2.1.0 那套混在一起的判据：万方一个检索式常只回 1 条题录，旧实现把页码写死成 1，
 * 于是第二扇窗口零新增就整轮判死（真机对账 reports/fullscan-live-3.txt：知网问 22 次、维普问 22 次、
 * 万方问 8 次，入库 0 条万方）。三个量各自的实测依据写在 docs/retrieval-recall.md 瓶颈三。
 *
 * <p>九个场景全走回环桩，一个真网络请求都不发：万方那一路是 gRPC-web 帧里裹 protobuf，
 * 桩按真实字段号造（1 状态、3 总数、4 资源、101 期刊载荷、104 我们解不出的载荷）。
 */
public final class WanfangPagingRegression {
    /** 每页一条全新的：游标该一路推到底。 */
    private static final int MODE_FRESH = 0;
    /** 每页都回同一条：只有零新增阈值有权在这里判收摊。 */
    private static final int MODE_STICKY = 1;
    /** 第 2 页起同一条 URL 换题名、同题名换 URL：去重只许认 URL。 */
    private static final int MODE_URL_KEY = 2;
    /** 第一页就凑够 perEngine：满足即停，一次配额也不许多花。 */
    private static final int MODE_FULL_PAGE = 3;
    /** 服务端声称总共 1 条：游标到此为止。 */
    private static final int MODE_NARROW = 4;
    /** 空帧：这一式零命中，不是取尽。 */
    private static final int MODE_EMPTY = 5;
    /** 一页 12 条全是解不出著录项的载荷：是我们的缺口，不许算成源没货。 */
    private static final int MODE_CONFERENCE_ONLY = 6;
    /** 一页里 9 条解不出、3 条是新的：缺口不许挡着游标走。 */
    private static final int MODE_PARSE_GAP = 7;
    /** 第 2 页报错：已经到手的题录不许一起作废。 */
    private static final int MODE_LATER_PAGE_FAILS = 8;
    /** 第一页就报错：这一路是真的失败，照原话抛上去。 */
    private static final int MODE_FIRST_PAGE_FAILS = 9;

    private static int checks;
    private static HttpServer server;
    private static String base = "";
    /** 桩从请求体里解出来的 currentPage 流水：游标按什么参数要页，就靠它验。 */
    private static final ArrayList<Integer> PAGES = new ArrayList<Integer>();
    /** 原样记下的请求体：游标参数与检索式都在字节里，验的是发出去了什么。 */
    private static final ArrayList<byte[]> REQUESTS = new ArrayList<byte[]>();
    private static int hitsCnki, hitsCqvip;
    private static int mode = MODE_FRESH;
    /** 桩在信封字段 3 里写的命中总数，负数表示不写这个字段（真接口的空帧就是这样）。 */
    private static long declaredTotal = -1L;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }
    private static synchronized void page(int value, byte[] body) {
        PAGES.add(Integer.valueOf(value));
        REQUESTS.add(body);
    }
    private static synchronized int pagesAsked() { return PAGES.size(); }
    private static synchronized int pageAt(int index) {
        return PAGES.get(index).intValue();
    }
    private static synchronized void reset(int nextMode, long total) {
        mode = nextMode;
        declaredTotal = total;
        PAGES.clear();
        REQUESTS.clear();
    }
    private static PaperSources.Limits limits() {
        PaperSources.Limits limits = new PaperSources.Limits();
        limits.perEngine = 12;
        limits.timeoutSeconds = 5;
        limits.proxy = "";
        return limits;
    }
    private static ArrayList<PaperSources.Candidate> wanfang() throws IOException {
        return PaperSources.search("wanfang", PHRASE, limits(), null);
    }
    /** 与真机那几扇窗口同一形态的检索式：多个词并列，万方按逐词 AND 处理。 */
    private static final String PHRASE = "碳化硅 瞬态液相扩散焊 界面组织 中间层 接头性能";
    private static boolean hasBytes(byte[] body, String needle) {
        byte[] wanted = needle.getBytes(StandardCharsets.UTF_8);
        if (body == null || wanted.length > body.length) return false;
        outer:
        for (int at = 0; at + wanted.length <= body.length; at++) {
            for (int i = 0; i < wanted.length; i++) if (body[at + i] != wanted[i]) continue outer;
            return true;
        }
        return false;
    }

    /** 从发出去的那一帧里解出 currentPage：游标用什么参数要页，必须量到字节，不许拿返回条数反推。 */
    private static int currentPageOf(byte[] body) {
        try {
            ArrayList<ProtoWire.Field> message =
                    ProtoWire.read(Arrays.copyOfRange(body, 5, body.length));
            ArrayList<byte[]> common = ProtoWire.nested(message, 1);
            if (common.isEmpty()) return 0;
            return (int) ProtoWire.number(ProtoWire.read(common.get(0)), 5);
        } catch (RuntimeException error) {
            return 0;
        }
    }
    private static int pageSizeOf(byte[] body) {
        try {
            ArrayList<ProtoWire.Field> message =
                    ProtoWire.read(Arrays.copyOfRange(body, 5, body.length));
            ArrayList<byte[]> common = ProtoWire.nested(message, 1);
            if (common.isEmpty()) return 0;
            return (int) ProtoWire.number(ProtoWire.read(common.get(0)), 6);
        } catch (RuntimeException error) {
            return 0;
        }
    }

    // ---- 回环桩 ----

    private static byte[] raw(HttpExchange exchange) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream input = exchange.getRequestBody();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) > 0) out.write(buffer, 0, count);
            return out.toByteArray();
        } catch (IOException error) {
            return new byte[0];
        }
    }
    private static void respondBytes(HttpExchange exchange, byte[] bytes) {
        try {
            exchange.getResponseHeaders().set("Content-Type", WanfangProtocol.CONTENT_TYPE);
            exchange.sendResponseHeaders(200, bytes.length);
            OutputStream out = exchange.getResponseBody();
            out.write(bytes);
            out.close();
        } catch (IOException ignored) { }
        exchange.close();
    }
    private static void respond(HttpExchange exchange, String text) {
        respondBytes(exchange, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/wanfang", exchange -> {
            byte[] body = raw(exchange);
            int page = currentPageOf(body);
            page(page, body);
            boolean explode = mode == MODE_FIRST_PAGE_FAILS || (mode == MODE_LATER_PAGE_FAILS && page > 1);
            if (explode) {
                try { exchange.sendResponseHeaders(500, -1); } catch (IOException ignored) { }
                exchange.close();
                return;
            }
            respondBytes(exchange, bodyFor(page));
        });
        server.createContext("/cnki", exchange -> {
            hitsCnki++;
            raw(exchange);
            respond(exchange, cnkiBody(hitsCnki, 12));
        });
        server.createContext("/cqvip", exchange -> {
            hitsCqvip++;
            raw(exchange);
            respond(exchange, cqvipBody(hitsCqvip, 12));
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 一条期刊著录项：字段号与 WanfangProtocol.addJournal 一一对齐（2 题名、3 作者、20 摘要、24 刊名、33 年）。 */
    private static byte[] journal(String id, String title, String abstr) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ProtoWire.writeString(body, 1, id);
        ProtoWire.writeString(body, 2, title);
        ProtoWire.writeString(body, 3, "张三");
        ProtoWire.writeString(body, 20, abstr);
        ProtoWire.writeString(body, 24, "焊接学报");
        ProtoWire.writeInt32(body, 33, 2024);
        ByteArrayOutputStream resource = new ByteArrayOutputStream();
        ProtoWire.writeString(resource, 1, "Periodical");
        ProtoWire.writeMessage(resource, 101, body.toByteArray());
        return resource.toByteArray();
    }
    /** 我们编不出著录项的载荷（会议 104）：它算得进信封里的条数，解不出候选。 */
    private static byte[] conference(String id) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        ProtoWire.writeString(body, 1, id);
        ProtoWire.writeString(body, 2, "会议论文" + id);
        ByteArrayOutputStream resource = new ByteArrayOutputStream();
        ProtoWire.writeString(resource, 1, "Conference");
        ProtoWire.writeMessage(resource, 104, body.toByteArray());
        return resource.toByteArray();
    }
    /** gRPC-web 一帧：1 字节标志 + 4 字节大端长度 + SearchResponse{1 状态, 3 总数, 4 资源}。 */
    private static byte[] frame(ArrayList<byte[]> resources, long total) {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        ProtoWire.writeInt32(message, 1, 1);
        ProtoWire.writeString(message, 2, "ok");
        if (total >= 0L) ProtoWire.writeInt32(message, 3, (int) total);
        for (byte[] resource : resources) ProtoWire.writeMessage(message, 4, resource);
        byte[] payload = message.toByteArray();
        byte[] framed = new byte[payload.length + 5];
        framed[1] = (byte) (payload.length >>> 24);
        framed[2] = (byte) (payload.length >>> 16);
        framed[3] = (byte) (payload.length >>> 8);
        framed[4] = (byte) payload.length;
        System.arraycopy(payload, 0, framed, 5, payload.length);
        return framed;
    }
    private static byte[] bodyFor(int page) {
        ArrayList<byte[]> items = new ArrayList<byte[]>();
        if (mode == MODE_STICKY) items.add(journal("STICKY", "题名一", "摘要一 中间层组织均匀"));
        else if (mode == MODE_URL_KEY) {
            if (page <= 1) items.add(journal("URLA", "题名一", "摘要一 中间层组织均匀"));
            else {
                items.add(journal("URLA", "题名二", "摘要二 界面反应层厚度"));
                items.add(journal("URLB", "题名一", "摘要三 保温时间延长"));
            }
        } else if (mode == MODE_FULL_PAGE) {
            for (int i = 1; i <= 12; i++)
                items.add(journal("FULL" + page + "_" + i, "多孔铜接头研究" + page + i,
                        "摘要 瞬态液相扩散焊 保温 硅化物 " + page + i));
        } else if (mode == MODE_NARROW) items.add(journal("NARROW1", "碳化硅连接研究", "摘要 瞬态液相扩散焊 中间层"));
        else if (mode == MODE_EMPTY) { /* 空帧：状态通过，不带总数也不带著录项，与真接口实测一致。 */ }
        else if (mode == MODE_CONFERENCE_ONLY) {
            for (int i = 1; i <= 12; i++) items.add(conference("CONF" + page + "_" + i));
        } else if (mode == MODE_PARSE_GAP) {
            for (int i = 1; i <= 9; i++) items.add(conference("CGAP" + page + "_" + i));
            for (int i = 1; i <= 3; i++) items.add(journal("GAP" + page + "_" + i,
                    "界面组织研究" + page + i, "摘要 瞬态液相扩散焊 界面 组织 " + page + i));
        } else items.add(journal("FRESH" + page, "中间层厚度研究" + page,
                "摘要 瞬态液相扩散焊 中间层 厚度 " + page));
        return frame(items, declaredTotal);
    }

    /** 知网条目页的最小可用形状：题名、出处、日期、摘要预览，与生产解析器认的骨架一致。 */
    private static String cnkiBody(int seq, int count) {
        StringBuilder out = new StringBuilder("<html><body>");
        for (int i = 1; i <= count; i++) {
            String code = "CND" + seq + "X" + i + ".CAJ";
            String title = "别家源对题研究" + seq + "_" + i;
            out.append("<div class=\"list-item\"><p class=\"source\" data-fn=\"").append(code).append("\">")
                    .append("<p class=\"tit\"><a href=\"https://chn.oversea.cnki.net/KCMS/detail/detail.aspx?dbcode=CJFD&filename=")
                    .append(code).append("\" title=\"").append(title).append("\">").append(title).append("</a></p>")
                    .append("<span title=\"焊接学报\">焊接学报</span><span>2024-03-01</span></p>")
                    .append("<p class=\"nr\">碳化硅瞬态液相扩散焊 界面组织 中间层 接头性能 第")
                    .append(seq).append('_').append(i).append("节。</p></div>");
        }
        return out.append("</body></html>").toString();
    }
    /** 维普条目页：服务端不给题名，题名由脚本后填，所以骨架里只认文献号与摘要。 */
    private static String cqvipBody(int seq, int count) {
        StringBuilder out = new StringBuilder("<html><body><div class=\"content-arrange\">");
        for (int i = 1; i <= count; i++) {
            out.append("<div class=\"item\"><a href=\"/doc/journal/CQV").append(seq).append("_").append(i)
                    .append("\" class=\"title\"></a><a href=\"/journal/J001\">焊接学报</a>")
                    .append("<a class=\"author-name\">张三</a> 2024年 第").append(i).append("期 ")
                    .append("<span class=\"abstr\">碳化硅瞬态液相扩散焊 界面组织 中间层 接头性能 第")
                    .append(seq).append('_').append(i).append("节。</span></div>");
        }
        return out.append("</div></body></html>").toString();
    }

    // ---- 场景 ----

    /** 翻页游标：第几页就按 currentPage 要第几页，页尺寸不许被顺手改掉。 */
    private static void cursorAdvancesByCurrentPage() throws Exception {
        reset(MODE_FRESH, -1L);
        wanfang();
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < pagesAsked(); i++) seen.append(pageAt(i)).append(' ');
        check(pagesAsked() == PaperSources.WANFANG_MAX_PAGES,
                "每页都有新东西时一路问到游标上限：" + seen.toString().trim());
        check(pageAt(0) == 1 && pageAt(1) == 2 && pageAt(pagesAsked() - 1) == PaperSources.WANFANG_MAX_PAGES,
                "游标按 CommonRequest.currentPage 要页，第 2 页真的按第 2 页要：" + seen.toString().trim());
        boolean pageSizeHolds = true, phraseHolds = true;
        for (int i = 0; i < REQUESTS.size(); i++) {
            if (pageSizeOf(REQUESTS.get(i)) != 12) pageSizeHolds = false;
            if (!hasBytes(REQUESTS.get(i), PHRASE)) phraseHolds = false;
        }
        check(pageSizeHolds, "换页只换页码，每页条数仍按 perEngine=12 要");
        check(phraseHolds, "每一页发的还是同一条检索式，正文不参与");
    }

    /** 每轮只回 1 条：判据必须在游标上限那一轮才收，而不是第 2 轮。 */
    private static void freshPagesAreNotExhaustion() throws Exception {
        reset(MODE_FRESH, -1L);
        ArrayList<PaperSources.Candidate> found = wanfang();
        check(pagesAsked() > 2, "每轮 1 条时不再第 2 轮判取尽：一共问了 " + pagesAsked() + " 页");
        check(found.size() == PaperSources.WANFANG_MAX_PAGES,
                "每一页那一条都进了结果，一条没丢：" + found.size() + " 条");
    }

    /** 同一条题录换页回来：要连续 WANFANG_ZERO_PAGE_LIMIT 页零新增才判取尽。 */
    private static void stickyPageNeedsThreeQuietPages() throws Exception {
        reset(MODE_STICKY, -1L);
        ArrayList<PaperSources.Candidate> found = wanfang();
        check(pagesAsked() == 1 + PaperSources.WANFANG_ZERO_PAGE_LIMIT,
                "同一条重复回来时问满 1 页有货加 " + PaperSources.WANFANG_ZERO_PAGE_LIMIT
                        + " 页零新增才收摊：实测 " + pagesAsked() + " 页");
        check(found.size() == 1, "重复回来的同一条只留一条：" + found.size() + " 条");
        check(pageAt(1) == 2 && pageAt(2) == 3, "零新增的那几页照样换页码要，不是拿同一页重放");
    }

    /** 页间去重只认 URL：同 URL 换题名不算新，同题名换 URL 不算重。 */
    private static void dedupIsByLocatorOnly() throws Exception {
        reset(MODE_URL_KEY, -1L);
        ArrayList<PaperSources.Candidate> found = wanfang();
        int first = 0, second = 0, sameTitle = 0;
        for (PaperSources.Candidate candidate : found) {
            if (candidate.source.locator.endsWith("/URLA")) first++;
            if (candidate.source.locator.endsWith("/URLB")) second++;
            if (candidate.source.title.contains("题名一")) sameTitle++;
        }
        check(found.size() == 2, "两条 URL 不同的题录都留下，尽管题名一模一样：" + found.size() + " 条");
        check(first == 1 && second == 1, "同一条 URL 换页回来只算一条：" + first + " 与 " + second);
        check(sameTitle == 2, "去重没拿题名当键，否则这里只剩一条：" + sameTitle + " 条同名");
        check(found.get(0).source.title.contains("题名一") && !found.get(0).source.title.contains("题名二"),
                "重复条目留第一次见到那条，后来的改写不许覆盖");
        check(pagesAsked() == 2 + PaperSources.WANFANG_ZERO_PAGE_LIMIT,
                "去重之后第 2 页确实只多出一条，零新增从头计起：" + pagesAsked() + " 页");
    }

    /** 满足即停：一次就凑够 perEngine 条，一次配额也不许多花。 */
    private static void fullPageStopsAtFulfilment() throws Exception {
        reset(MODE_FULL_PAGE, 9999L);
        ArrayList<PaperSources.Candidate> found = wanfang();
        check(found.size() == 12, "第一页就凑够 12 条：" + found.size());
        check(pagesAsked() == 1, "凑够 perEngine 就不再推游标：" + pagesAsked() + " 页");
    }

    /** 服务端声称总共 1 条：游标到此为止，这就是真机那六扇空窗口的形状。 */
    private static void declaredTotalStopsTheCursor() throws Exception {
        reset(MODE_NARROW, 1L);
        ArrayList<PaperSources.Candidate> found = wanfang();
        check(found.size() == 1, "总数 1 条的窄检索式拿回 1 条：" + found.size());
        check(pagesAsked() == 1, "服务端说总共 1 条就不再翻页，不拿配额去撞空页：" + pagesAsked() + " 页");
    }

    /** 空帧是没命中，不是取尽：一次问完就收，零新增那一串也不许被它点燃。 */
    private static void emptyFrameIsNoHit() throws Exception {
        reset(MODE_EMPTY, -1L);
        ArrayList<PaperSources.Candidate> found = wanfang();
        check(found.isEmpty(), "空帧回 0 条：" + found.size());
        check(pagesAsked() == 1, "整帧没带著录项就当场收摊：实测 " + pagesAsked() + " 页（真接口 18/18 次换页也还是空）");
    }

    /** 一页 12 条全解不出著录项：那是我们的解析缺口，只能按零新增计，不能当场判死。 */
    private static void unparseablePayloadIsNotNoStock() throws Exception {
        reset(MODE_CONFERENCE_ONLY, -1L);
        ArrayList<PaperSources.Candidate> found = wanfang();
        check(found.isEmpty(), "解不出著录项的载荷不产候选：" + found.size());
        check(pagesAsked() == PaperSources.WANFANG_ZERO_PAGE_LIMIT,
                "带著录项但零新增的页要走满阈值才收，不是第一页就当没货：" + pagesAsked() + " 页");
    }

    /** 一页里混着解不出的条目：只要还有新的解得出来，游标就继续走。 */
    private static void parseGapKeepsCursorMoving() throws Exception {
        reset(MODE_PARSE_GAP, -1L);
        ArrayList<PaperSources.Candidate> found = wanfang();
        check(found.size() == 12, "每页 12 条里只解出 3 条时，缺口不许冒充没货，照旧凑满 perEngine："
                + found.size() + " 条");
        check(pagesAsked() == 4, "解不出的那九条不算零新增，四页凑满十二条才收：" + pagesAsked() + " 页");
        check(PaperSources.WANFANG_MAX_PAGES > 4, "游标上限留有余量，凑单不会被上限当成取尽");
    }

    /** 后面几页失败只该少拿几条：已经到手的题录不许一起作废；第一页失败仍要照原话抛。 */
    private static void laterPageFailureKeepsCandidates() throws Exception {
        reset(MODE_LATER_PAGE_FAILS, -1L);
        ArrayList<PaperSources.Candidate> found = wanfang();
        check(found.size() == 1, "第 2 页报错时第一页那条照样留下：" + found.size());
        check(pagesAsked() == 2, "报错之后不再往下推游标：" + pagesAsked() + " 页");
        reset(MODE_FIRST_PAGE_FAILS, -1L);
        boolean thrown = false;
        try { wanfang(); } catch (IOException error) { thrown = true; }
        check(thrown, "第一页就答不通时照原话抛 IOException，别静默交白卷");
    }

    /** 别家源用过的题录不许替万方下结论：知网易普每次都满载，也不许改变万方的页数与条数。 */
    private static void otherSourcesCannotSpeakForWanfang() throws Exception {
        reset(MODE_STICKY, -1L);
        ArrayList<PaperSources.Candidate> alone = wanfang();
        int alonePages = pagesAsked();
        reset(MODE_STICKY, -1L);
        hitsCnki = 0;
        hitsCqvip = 0;
        ArrayList<String> perRound = new ArrayList<String>();
        int cnki = 0, cqvip = 0;
        for (int round = 0; round < 3; round++) {
            cnki = PaperSources.search("cnki", PHRASE, limits(), null).size();
            cqvip = PaperSources.search("cqvip", PHRASE, limits(), null).size();
            int before = pagesAsked();
            ArrayList<PaperSources.Candidate> found = wanfang();
            perRound.add((pagesAsked() - before) + "/" + found.size());
        }
        check(cnki == 12 && cqvip == 12, "知网与维普每次都满载回 12 条：" + cnki + " 与 " + cqvip);
        check(hitsCnki == 3 && hitsCqvip == 3, "别家源各被问了三次，它们的命中不进万方的账");
        String expected = alonePages + "/" + alone.size();
        boolean identical = true;
        for (String one : perRound) if (!one.equals(expected)) identical = false;
        check(identical, "万方每轮的页数与条数和单独跑时一致，别家满载也不改变它的判定："
                + perRound + "，单独跑是 " + alonePages + " 页 " + alone.size() + " 条");
        check(perRound.size() == 3, "连问三轮，一轮也不许被别家的题录替万方提前收摊");
        check(alonePages == 1 + PaperSources.WANFANG_ZERO_PAGE_LIMIT,
                "单独跑也是问满 1 页有货加零新增阈值：" + alonePages + " 页");
    }

    public static void main(String[] args) throws Exception {
        long savedGap = PaperSources.wanfangPageGapMillis;
        try {
            PaperSources.wanfangPageGapMillis = 0L;
            start();
            PaperSources.setEndpoint("wanfang", base + "/wanfang");
            PaperSources.setEndpoint("cnki", base + "/cnki");
            PaperSources.setEndpoint("cqvip", base + "/cqvip");
            cursorAdvancesByCurrentPage();
            freshPagesAreNotExhaustion();
            stickyPageNeedsThreeQuietPages();
            dedupIsByLocatorOnly();
            fullPageStopsAtFulfilment();
            declaredTotalStopsTheCursor();
            emptyFrameIsNoHit();
            unparseablePayloadIsNotNoStock();
            parseGapKeepsCursorMoving();
            laterPageFailureKeepsCandidates();
            otherSourcesCannotSpeakForWanfang();
        } finally {
            PaperSources.wanfangPageGapMillis = savedGap;
            PaperSources.resetEndpoints();
            if (server != null) server.stop(0);
        }
        System.out.println("SUMMARY " + checks + " assertions passed; loopback-only network");
    }
}

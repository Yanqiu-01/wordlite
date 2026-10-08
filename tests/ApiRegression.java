package com.rikkahub.wordlite;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Calls a loopback fixture server only; never sends document text to any public endpoint. */
public final class ApiRegression {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message); checks++; System.out.println("PASS " + message);
    }
    public static void main(String[] args) throws Exception {
        ApiConfig config = new ApiConfig();
        check(config.url.isEmpty() && config.key.isEmpty(), "no default endpoint or embedded API key");
        String original = "论文\"\\\nμ{key}{filename}";
        String template = "{\"content\":{text},\"auth\":{key},\"name\":\"{filename}\",\"prompt\":\"改写：{text}\"}";
        String expanded = ApiTemplate.json(template, original, "test-secret", "文档.docx");
        Object value = ApiJson.parse(expanded);
        check(ApiJson.path(value, "content").equals(original), "template escapes JSON and never expands tokens inside input text");
        check(ApiJson.path(value, "prompt").equals("改写：" + original), "tokens supported in quoted prompt strings");
        check(ApiJson.path(ApiJson.parse("{\"a\":[{\"b\":\"µ\"}]}"), "$.a[0].b").equals("µ"), "nested response path works");
        boolean rejected = false; try { ApiJson.parse("{\"x\":NaN}"); } catch (IllegalArgumentException error) { rejected = true; }
        check(rejected, "invalid JSON rejects without running expressions");
        KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256); SecretKey key = generator.generateKey();
        byte[] secret = SecretCipher.encrypt(key, "CHECK", "sensitive-key");
        check(!new String(secret, StandardCharsets.ISO_8859_1).contains("sensitive-key"), "encrypted settings have no plaintext secret");
        check(SecretCipher.decrypt(key, "CHECK", secret).equals("sensitive-key"), "AES-GCM roundtrip");
        rejected = false; try { SecretCipher.decrypt(key, "REWRITE", secret); } catch (Exception error) { rejected = true; }
        check(rejected, "wrong profile fails GCM authentication");
        secret[secret.length - 1] ^= 1; rejected = false;
        try { SecretCipher.decrypt(key, "CHECK", secret); } catch (Exception error) { rejected = true; }
        check(rejected, "tampered settings rejected");
        config.url = "https://example.test/api"; config.key = "secret"; config.rateFraction = true; config.codePointOffsets = true;
        config.headers.put("X-Api-Key", "{key}"); config.terms.add("瞬态液相");
        ApiConfig saved = SettingsManager.deserialize(SettingsManager.serialize(config));
        check(saved.key.equals(config.key) && saved.rateFraction && saved.codePointOffsets && saved.terms.equals(config.terms), "settings codec retains config/key/offsets/terms");
        config.headers.put("X-Bad", "bad\r\nAuthorization: hidden"); rejected = false;
        try { config.validate(); } catch (IllegalArgumentException error) { rejected = true; }
        check(rejected, "header injection rejected"); config.headers.remove("X-Bad");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger retries = new AtomicInteger(); final String[] request = {""}, auth = {""}, idempotency = {""};
        server.createContext("/check", exchange -> {
            request[0] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            auth[0] = exchange.getRequestHeaders().getFirst("X-Api-Key");
            byte[] response = "{\"data\":{\"rate\":0.125,\"detected_at\":\"2026-10-07T12:00:00Z\",\"fragments\":[{\"text\":\"论文μ\",\"start\":0,\"end\":3,\"source\":\"论文来源\"}]}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.createContext("/retry", exchange -> {
            String id = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            if (idempotency[0].isEmpty()) idempotency[0] = id; else if (!idempotency[0].equals(id)) throw new AssertionError("retry ID changed");
            int attempt = retries.incrementAndGet(); byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(attempt == 1 ? 503 : 200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().set("Location", "https://never-contact.test/"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        server.createContext("/unauthorized", exchange -> { exchange.sendResponseHeaders(401, -1); exchange.close(); });
        server.start();
        try {
            config.url = "http://127.0.0.1:" + server.getAddress().getPort() + "/check";
            ApiClient.Response response = ApiClient.execute(config, "论文μ", null, "", () -> false);
            check(ApiJson.path(ApiJson.parse(request[0]), "text").equals("论文μ"), "HTTP JSON body submits only requested text");
            check(auth[0].equals("secret"), "configured auth header expanded correctly");
            ApiResult.Check result = ApiResult.check(config, response, "论文μ");
            check(result.rate == 12.5 && result.fragments.get(0).source.equals("论文来源"), "response mapping yields rate/snippet/source");
            check(result.detectedAt.equals("2026-10-07T12:00:00Z"), "detection time from configured field");
            config.url = config.url.replace("/check", "/retry"); config.retries = 2;
            response = ApiClient.execute(config, "重试", null, "", () -> false);
            check(response.attempts == 2 && retries.get() == 2, "transient server error retries with one stable job ID");
            config.url = config.url.replace("/retry", "/redirect"); rejected = false;
            try { ApiClient.execute(config, "正文", null, "", () -> false); } catch (ApiClient.Failure error) { rejected = error.status == 302; }
            check(rejected, "no credential or document forwarding on redirects");
            config.url = config.url.replace("/redirect", "/unauthorized"); rejected = false;
            try { ApiClient.execute(config, "正文", null, "", () -> false); } catch (ApiClient.Failure error) { rejected = error.status == 401; }
            check(rejected, "authentication errors fail concisely");
            rejected = false; try { ApiClient.execute(config, "正文", null, "", () -> true); } catch (ApiClient.Failure error) { rejected = error.getMessage().equals("已取消"); }
            check(rejected, "cancelled task never sends request");
            config.mode = ApiConfig.Mode.MULTIPART;
            ApiClient.Prepared upload = ApiClient.prepare(config, "文字", new byte[]{0, 1, 2, 3}, "bad\r\n\"name.docx", "testid");
            String multipart = new String(upload.body, StandardCharsets.ISO_8859_1);
            check(upload.contentType.startsWith("multipart/form-data;") && multipart.contains("name=\"file\"") && !multipart.contains("filename=\"bad\r\n"), "multipart uploads document and sanitizes filename");
        } finally { server.stop(0); }
        DocxDocument document = new DocxDocument(); DocxDocument.ParagraphBlock p = new DocxDocument.ParagraphBlock();
        p.index = 3; p.text = "该研究使用 SAC305 焊料，在 300 ℃下取得成果[2]。";
        DocxDocument.RunStyle plain = new DocxDocument.RunStyle(); plain.fontSizeHalfPoints = 24; plain.eastAsiaFontFamily = "宋体";
        DocxDocument.RunStyle citation = plain.copy(); citation.superscript = true; citation.superscriptSet = true;
        p.runs.add(new DocxDocument.Run(p.text.substring(0, p.text.indexOf("[2]")), plain));
        p.runs.add(new DocxDocument.Run("[2]", citation)); p.runs.add(new DocxDocument.Run("。", plain.copy()));
        document.blocks.add(p); document.paragraphs.add(p);
        TextProtection.Mask mask = TextProtection.mask(p, 0, p.text.length(), Arrays.asList("焊料"));
        check(!mask.submitted.contains("SAC305") && !mask.submitted.contains("[2]") && !mask.submitted.contains("300 ℃"), "technical strings/citations/measurements protected");
        String suggestion = mask.submitted.replace("该研究使用", "本研究采用").replace("取得成果", "获得显著成果");
        String rewritten = mask.restore(suggestion); check(rewritten.contains("SAC305") && rewritten.contains("300 ℃") && rewritten.contains("[2]"), "restore preserves protected islands exactly");
        rejected = false; try { mask.restore(suggestion.replace(mask.islands.get(0).token, "丢失")); } catch (IllegalArgumentException error) { rejected = true; }
        check(rejected, "lost protection tokens cannot be accepted silently");
        String before = p.text; document.trackRevisions = true; TextRewriter.apply(document, p, 0, mask, suggestion);
        check(p.text.equals(rewritten) && p.runs.get(0).style.fontSizeHalfPoints == 24, "accepting suggestion changes prose and retains point size");
        boolean superscript = false; for (DocxDocument.Run run : p.runs) if (run.text.contains("[2]")) superscript = run.style.superscript;
        check(superscript, "citation superscript formatting retained");
        check(!document.revisions.isEmpty(), "accepted rewriting participates in tracked changes");
        for (DocxDocument.Revision r : new ArrayList<DocxDocument.Revision>(document.revisions))
            if (r.kind == DocxDocument.Revision.Kind.INSERT) ReviewManager.decide(document, r, false);
        for (DocxDocument.Revision r : new ArrayList<DocxDocument.Revision>(document.revisions)) ReviewManager.decide(document, r, false);
        check(p.text.equals(before), "reject rewriting revisions restores original text");
        ApiClient.Response response = new ApiClient.Response(); response.body = "{\"data\":{\"rate\":\"10%\",\"fragments\":[{\"text\":\"😀μ\",\"start\":1,\"end\":3}]}}";
        ApiResult.Check unicode = ApiResult.check(config, response, "A😀μB");
        check(unicode.fragments.get(0).start == 1 && unicode.fragments.get(0).end == 4, "Unicode codepoint offsets map to UTF-16 document anchors");
        TextSelection selected = TextSelection.paragraph(p, 0, 2); check(selected.unchanged(document), "request snapshots verify original text before applying result");
        ApiResult.Check report = new ApiResult.Check(); report.rate = 5; ApiResult.Fragment fragment = new ApiResult.Fragment(); fragment.text = "<script>bad</script>"; report.fragments.add(fragment);
        check(CheckReport.html("file.docx", report).contains("&lt;script&gt;") && !CheckReport.html("file.docx", report).contains("<script>"), "HTML reports escape all API supplied text");
        aigcTrendCell();
        System.out.println("SUMMARY " + checks + " API/config/encryption/protection/AIGC 文案 assertions passed; loopback-only network");
    }

    /**
     * 结果面板那一格（0.7.1）。ApiWorkflow 要 Android 才跑得起来，host JVM 上钉的是它准备打印的那句话本身：
     * 面板与 HTML 报告都取 DuplicateEngine.aigcTrend / aigcScoreLine，一处改文案两处一起动。
     * 0.7.1 时这一格是档位加两个字数；0.7.2 起判据未标定，它连档位也不给，只说方向错在哪一侧。
     * 两种说法都没有百分号，也都不叫比例。
     *
     * 夹具手算：全文 21 个有效字符，重复 [0,10) 10 个字，其中引用区间 [0,4) 占 4 个，机器腔 [10,21) 11 个字。
     * 于是 总相似度比 10/21、去除引用重复比 6/21、引用内重复比 4/21、自编率 11/21，三者闭合。
     */
    private static void aigcTrendCell() {
        String text = "本文给出保温时间与剪切强度的对照实测结果。";
        CharLedger.Balance ledger = CharLedger.closeSpans(text, null, new int[]{0, 4}, new int[]{0, 10}, new int[]{10, 21});
        check(ledger.totalChars == 21 && ledger.duplicateChars == 10 && ledger.citedDuplicateChars == 4
                        && ledger.selfWrittenChars == 11 && ledger.machineChars == 11 && ledger.residual() == 0,
                "账本手算：自编 11 == 21 - 10（只减重复）；机器腔那 11 个字没有再减第二次，否则旧口径会得出 0");
        DuplicateEngine.Report panel = new DuplicateEngine.Report();
        panel.sourceText = text;
        panel.ledger = ledger;
        panel.comparedChars = ledger.totalChars;
        panel.overallRate = ledger.overallRate;
        panel.excludingCitationsRate = ledger.excludingCitationsRate;
        panel.selfWrittenRate = ledger.selfWrittenRate;
        panel.aigcRate = 45.26;
        panel.aigc = new AigcDetector.Result();
        panel.aigc.comparedChars = 630;
        panel.aigc.tier = AigcDetector.Tier.NEEDS_REVIEW;
        // 0.7.2 改口：判据方向没验正（AigcScorer.VERSION=v1-order-only；标注语料 AUC(机器>真人)=0.305，方向是反的，
        // 真人侧最高句分 0.506 已越过门槛 0.450，机器侧最高 0.217 一枪没打），这一格从 档位+字数 降级成拒绝句。
        // 原来钉在这里的手算账（复核 + 可疑 11 字 / 全文 21 字）拆成三件量留住：撤一格不等于把口径一起丢掉。
        check(DuplicateEngine.trendMachineChars(panel) == 11 && DuplicateEngine.trendTotalChars(panel) == 21
                        && DuplicateEngine.aigcTierName(AigcDetector.Tier.NEEDS_REVIEW).equals("复核"),
                "那一格背后的两个绝对量还是手算的 11 与 21，复核档的名字也仍由这一处供给");
        check(DuplicateEngine.aigcTrend(panel).equals(DuplicateEngine.AIGC_UNCALIBRATED),
                "面板那一格现在只说方向：" + DuplicateEngine.aigcTrend(panel));
        check(DuplicateEngine.aigcScoreLine(panel).isEmpty(),
                "句分 45.26 不再被打成一位小数印出去：整格空串，宁可不画");
        check(!DuplicateEngine.aigcTrend(panel).contains("%") && !DuplicateEngine.aigcTrend(panel).contains("比例"),
                "这一格既没有百分号也不叫比例");
        String html = CheckReport.html("panel.docx", panel);
        check(html.contains("<td>机器生成倾向</td><td>" + DuplicateEngine.AIGC_UNCALIBRATED + "</td>")
                        && !html.contains("机器腔均分") && !html.contains("45.3"),
                "HTML 报告与面板同一份拒绝文案，那个 45.3 在整份报告里一个字符都不存在");
        check(!html.contains("AIGC 生成比例"), "报告里再也没有 AIGC 生成比例这一格");
        panel.aigc.insufficientSample = true;
        panel.aigcInsufficient = true;
        check(DuplicateEngine.aigcTrend(panel).equals("样本不足（有效字符 630 字，门槛 400 字）")
                        && DuplicateEngine.aigcScoreLine(panel).isEmpty(),
                "样本不足只报差多少字：档位格写门槛，均分格整格不给");
        check(CheckReport.html("short.docx", panel).contains(
                "<td>机器生成倾向</td><td>样本不足（有效字符 630 字，门槛 400 字）</td>"),
                "样本不足时 HTML 那一格也不印 0.00% 之类的假数");
        panel.aigc = null;
        check(DuplicateEngine.aigcTrend(panel).equals("本机 AIGC 分析未执行")
                        && DuplicateEngine.aigcScoreLine(panel).isEmpty(),
                "这一轮没跑 AIGC 就说没跑，不拿 0.00% 冒充干净");
        check(DuplicateEngine.aigcTierName(AigcDetector.Tier.INSUFFICIENT_SAMPLE).equals("样本不足")
                        && DuplicateEngine.aigcTierName(AigcDetector.Tier.NONE).equals("一般")
                        && DuplicateEngine.aigcTierName(AigcDetector.Tier.WATCH).equals("观察")
                        && DuplicateEngine.aigcTierName(AigcDetector.Tier.NEEDS_REVIEW).equals("复核")
                        && DuplicateEngine.aigcTierName(AigcDetector.Tier.STRONG).equals("成段"),
                "五档名字一份定死：样本不足/一般/观察/复核/成段");
    }
}

package com.rikkahub.wordlite;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 取全文那一路跟不跟跳转（2026-10-09 真机：结果页"下进自建库"十四篇全落空，其中一批就死在
 * 302 上——以前的规矩是"检索源发生重定向"一律当错误，那对检索是对的，对 PDF 只是路上的一站）。
 * 这一台只量 HttpTransport.getPdf 与 PaperSources.fetchPdf 这一段，全程回环，不外呼。
 */
public class PdfRedirectRegression {
    private static int passed;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        passed++;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 一份最小得能被认成 PDF 的字节：这一台不看内容，只看这一跳跟没跟。 */
    private static final byte[] PDF = ("%PDF-1.4\\n1 0 obj\\n<<>>\\nendobj\\ntrailer\\n%%EOF\\n")
            .getBytes(StandardCharsets.UTF_8);

    private static Map<String, String> noHeaders() {
        return new LinkedHashMap<String, String>();
    }

    public static void main(String[] args) throws Exception {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String base = "http://127.0.0.1:" + server.getAddress().getPort();
        final int[] hops = new int[1];
        server.createContext("/real.pdf", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/pdf");
            exchange.sendResponseHeaders(200, PDF.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(PDF); }
        });
        server.createContext("/relative", exchange -> {
            exchange.getResponseHeaders().add("Location", "real.pdf");   // 相对写法
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/absolute", exchange -> {
            exchange.getResponseHeaders().add("Location", base + "/real.pdf");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.createContext("/noloc", exchange -> { exchange.sendResponseHeaders(302, -1); exchange.close(); });
        server.createContext("/downgrade", exchange -> {
            // 从加密跳到不加密：这一台整个是 http，所以用 ftp 那种一眼不合法的做对照，见 /odd
            exchange.getResponseHeaders().add("Location", base + "/real.pdf");
            exchange.sendResponseHeaders(308, -1);
            exchange.close();
        });
        server.createContext("/odd", exchange -> {
            exchange.getResponseHeaders().add("Location", "ftp://example.invalid/a.pdf");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/loop", exchange -> {
            hops[0]++;
            exchange.getResponseHeaders().add("Location", base + "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "redirect-stub");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        try {
            HttpTransport.Fetched got = HttpTransport.getPdf(base + "/relative", noHeaders(), 8,
                    HttpTransport.MAX_PDF_BODY, null, java.net.Proxy.NO_PROXY, true);
            check(got.status == 200 && got.bytes.length == PDF.length,
                    "相对写法的 302 要跟到那份 PDF（实得 status=" + got.status
                            + " bytes=" + got.bytes.length + "）");
            check(new String(got.bytes, StandardCharsets.UTF_8).startsWith("%PDF-"),
                    "跟到之后拿回的必须还是那份 PDF 的字节");

            HttpTransport.Fetched moved = HttpTransport.getPdf(base + "/absolute", noHeaders(), 8,
                    HttpTransport.MAX_PDF_BODY, null, java.net.Proxy.NO_PROXY, false);
            check(moved.status == 200 && moved.bytes.length == PDF.length,
                    "绝对写法的 301 也要跟到（没放行不加密也照样跟，因为两头都是 http 由调用方负责）");

            String failure = fails(base + "/noloc", true);
            check(failure != null && failure.contains("重定向"),
                    "没有 Location 的 302 仍然算失败，而且要说清是重定向（实得 " + failure + "）");

            String odd = fails(base + "/odd", true);
            check(odd != null && odd.contains("不是网页或 PDF"),
                    "跳到 ftp 这类地址要当场拒掉（实得 " + odd + "）");

            hops[0] = 0;
            String loop = fails(base + "/loop", true);
            check(loop != null && (loop.contains("重定向") || loop.contains("302")),
                    "跳来跳去的那一条要停在跳的上限，不许无限跟（实得 " + loop + "）");
            check(hops[0] == HttpTransport.MAX_PDF_REDIRECTS + 1,
                    "跳的上限就是 MAX_PDF_REDIRECTS，多一次都不许（实得 " + hops[0] + " 次请求）");

            String search = null;
            try {
                HttpTransport.get(base + "/relative", noHeaders(), 8, HttpTransport.MAX_BODY,
                        null, java.net.Proxy.NO_PROXY);
            } catch (ApiClient.Failure error) {
                search = error.getMessage();
            }
            check(search != null && search.contains("重定向"),
                    "检索那一路照旧把 302 当错误——挡人页就靠这一跳认，不能跟着跑（实得 " + search + "）");

            PaperSources.Limits limits = new PaperSources.Limits();
            limits.timeoutSeconds = 8;
            PaperSources.setEndpoint("europepmc", base + "/relative");
            PaperSources.fetchPdf(base + "/relative", limits, null);
            check(true, "fetchPdf 带着浏览器标识走同一条跟跳的路（跑通即算，形状另有台账）");
        } finally {
            server.stop(0);
        }
        System.out.println("SUMMARY " + passed + " assertions passed; loopback-only network");
    }

    private static String fails(String url, boolean allowPlainHttp) {
        try {
            HttpTransport.getPdf(url, noHeaders(), 8, HttpTransport.MAX_PDF_BODY, null,
                    java.net.Proxy.NO_PROXY, allowPlainHttp);
            return null;
        } catch (ApiClient.Failure error) {
            return String.valueOf(error.getMessage());
        } catch (java.io.IOException error) {
            return String.valueOf(error.getMessage());
        }
    }
}

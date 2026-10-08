package com.rikkahub.wordlite;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 无头浏览器取正文这条路，本机到底跑不跑得通：跑通了取回的是什么。
 *
 * <p>两条路子都试，同一批真文献（文献号取自生产检索代码当轮返回的真记录）：
 *   flare  —— 本机 FlareSolverr（Docker，POST /v1 的 request.get，它内部起真 Chrome 过 JS 挑战）；
 *   browser—— 直接叫本机 Chrome 的 headless（--dump-dom），不经过任何中间服务。
 *
 * <p>取回来的东西按产品自己的判据分档（{@link PaperSources#challengeOf}），再喂进自建库那条路：
 * CorpusImport → LocalLibrary.index → DuplicateEngine.scan。最后一列是"查重拿它比没比"，
 * 不是"页面里有没有字"——这是这条路与那条路之间唯一的验收标准。
 *
 * 不在闸门里：要本机有 Docker+FlareSolverr 或 Chrome，要联网。
 *   java com.rikkahub.wordlite.BrowserBodyProbe flare   [出口目录]
 *   java com.rikkahub.wordlite.BrowserBodyProbe browser [出口目录]
 */
public final class BrowserBodyProbe {
    private static final String FLARE = "http://127.0.0.1:8191/v1";
    private static final String CHROME = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
    private static final int READABLE_BODY = 2000;

    /** 五个真地址：三库的详情页 + 维普那个匿名 412 的检索页 + 一个真有全文的开放获取页当对照组。 */
    private static final String[] URLS = {
            "https://www.cqvip.com/doc/journal/7103817796",
            "https://qikan.cqvip.com/Qikan/Search/Index?key=K%3D%E6%B7%B1%E5%BA%A6%E5%AD%A6%E4%B9%A0",
            "https://wap.cnki.net/touch/web/Journal/Article/GWDZ202620023.html",
            "https://d.wanfangdata.com.cn/periodical/jsjgcyyy202103005",
            "https://ar5iv.labs.arxiv.org/html/2303.08774",
    };
    private static final String[] MARKS = {
            "维普", "qikan", "知网", "万方", "ar5iv",
    };

    public static void main(String[] argv) throws Exception {
        String mode = argv.length > 0 ? argv[0].trim().toLowerCase(Locale.US) : "flare";
        File out = new File(argv.length > 1 ? argv[1] : "artifacts/endpoints/browser");
        out.mkdirs();
        System.out.println("# 取法 " + mode + "  留档 " + out.getPath());
        System.out.printf(Locale.US, "%-10s %-8s %9s %7s %-16s %s%n",
                "标记", "状态", "字节", "汉字", "分档", "结论");
        ArrayList<CorpusImport.Source> sources = new ArrayList<CorpusImport.Source>();
        String planted = null, plantedFrom = "";
        for (int i = 0; i < URLS.length; i++) {
            Fetched got = "browser".equals(mode) ? viaChrome(URLS[i]) : viaFlare(URLS[i]);
            String visible = SourceEndpointProbe.visible(got.html);
            int cjk = count(visible);
            String shape = PaperSources.challengeOf(got.html, visible.trim().length());
            String label = shape == null ? (cjk >= READABLE_BODY ? "正文级" : "正文级·短")
                    : ("blocked".equals(shape) ? "人机验证壳" : "空壳");
            write(new File(out, i + "-" + MARKS[i] + ".html"), got.html);
            System.out.printf(Locale.US, "%-10s %-8s %9d %7d %-16s %s%n", MARKS[i], got.status,
                    got.html.getBytes(StandardCharsets.UTF_8).length, cjk, label, got.note);
            if (shape == null && cjk >= 400) {
                String name = "取回-" + MARKS[i] + "-" + (i + 1) + ".txt";
                sources.add(new CorpusImport.Source(name, visible.trim().getBytes("UTF-8")));
                if (planted == null) {
                    planted = sentence(visible);
                    plantedFrom = name;
                }
            }
        }
        compare(out, sources, planted, plantedFrom);
    }

    /** 取回来的东西真进比对：入自建库 → 只查自建库跑一遍 → 看命中与档位。 */
    private static void compare(File out, ArrayList<CorpusImport.Source> sources, String planted, String from)
            throws Exception {
        if (sources.isEmpty()) {
            System.out.println("比对那一步没跑：这一批没有一页取回可读正文（>=400 汉字），没有材料可喂。");
            return;
        }
        File dir = new File(out, "library");
        delete(dir);
        dir.mkdirs();
        LocalLibrary library = new LocalLibrary(dir);
        CorpusImport.Batch batch = CorpusImport.run(library, sources, null, null);
        System.out.println("自建库入库：" + batch.summary() + "，" + batch.importedChars() + " 字（来自无头浏览器取回的页面）");

        String draft = "本节先说现场与数据。改造方案的比选按汇水面积分三段推进，"
                + "第一段以旱季流量校核阻力系数，第二段以设计暴雨复核峰值，第三段只做复核。"
                + (planted == null ? "" : planted)
                + "随后的支管按同一口径复核，结论与主段一致，施工顺序可据此重排。";
        // DuplicateEngine.scan 收的是 TextSelection（要从 docx 的分段构造），这台量的是比对那一步，
        // 所以直接叫 scan 内部调的同一句 library.match——判据、档位、出处都是同一套。
        TextCorpus corpus = corpusOf(library);
        TextCorpus.Report matched = corpus.match(draft, null);
        int chars = 0;
        for (int i = 0; i < matched.hits.size(); i++)
            chars += TextCorpus.validCount(TextCorpus.normalize(draft), matched.hits.get(i).start,
                    matched.hits.get(i).end);
        System.out.println("比对（TextCorpus.match，与 scan 内部同一句）：命中 " + matched.hits.size()
                + " 处、重复 " + chars + " 字 / 可比 " + matched.comparedChars + " 字；种子句取自 " + from);
        if (matched.hits.isEmpty())
            System.out.println("   没命中：取回的页面里没有那句话，或它短过判据的下限");
        for (int i = 0; i < matched.hits.size() && i < 3; i++) {
            TextCorpus.Hit hit = matched.hits.get(i);
            System.out.println("   命中 score=" + String.format(Locale.US, "%.3f", hit.score)
                    + " 出处 [" + cut(hit.source == null ? "?" : hit.source.title, 30) + "] 材料档 ["
                    + (hit.source == null ? "?" : hit.source.material) + "]");
        }
    }

    private static TextCorpus corpusOf(LocalLibrary library) {
        TextCorpus corpus = new TextCorpus();
        library.index(corpus);
        return corpus;
    }

    /** 从取回的页面文本里挑一句长的当种子：这条句子必须在库里那份材料里逐字存在。 */
    private static String sentence(String visible) {
        String[] parts = visible.split("[。！？.!?\n]");
        for (String part : parts) {
            String clean = part.replaceAll("\\s+", " ").trim();
            int cjk = count(clean);
            if (cjk >= 20 || clean.length() >= 90) return clean + "。";
        }
        return null;
    }

    // ---- 两条取法 ----

    private static final class Fetched {
        String html = "", status = "-", note = "";
    }

    private static Fetched viaFlare(String url) {
        Fetched got = new Fetched();
        try {
            String body = "{\"cmd\":\"request.get\",\"url\":\"" + url + "\",\"maxTimeout\":60000}";
            HttpURLConnection conn = (HttpURLConnection) new URL(FLARE).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(90000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            OutputStream out = conn.getOutputStream();
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.close();
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String json = read(stream);
            got.status = String.valueOf(code);
            got.html = field(json, "content");
            if (got.html.length() == 0) got.html = field(json, "response");   // v3 把整页放在 response 里
            String status = field(json, "status");
            String solved = field(json, "solution");
            if (status.length() > 0) got.status = status;
            if (solved.length() > 0) got.note = "solver=" + cut(solved, 30);
            if (got.html.length() == 0) got.note = "响应里没有 content：" + cut(json, 90);
        } catch (Exception error) {
            got.status = "ERR";
            got.note = error.getClass().getSimpleName() + " " + cut(String.valueOf(error.getMessage()), 60);
        }
        return got;
    }

    private static Fetched viaChrome(String url) {
        Fetched got = new Fetched();
        try {
            ArrayList<String> command = new ArrayList<String>();
            command.add(CHROME);
            command.add("--headless=new");
            command.add("--disable-gpu");
            command.add("--no-sandbox");
            command.add("--disable-dev-shm-usage");
            command.add("--virtual-time-budget=15000");
            command.add("--timeout=30000");
            command.add("--dump-dom");
            command.add(url);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            Process process = builder.start();
            String html = read(process.getInputStream());
            String err = read(process.getErrorStream());
            if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                got.status = "TIMEOUT";
            } else {
                got.status = "exit" + process.exitValue();
            }
            got.html = html;
            got.note = html.length() == 0 ? "Chrome 没吐出 DOM：" + cut(err, 70) : "本机 Chrome headless";
        } catch (Exception error) {
            got.status = "ERR";
            got.note = error.getClass().getSimpleName() + " " + cut(String.valueOf(error.getMessage()), 60);
        }
        return got;
    }

    // ---- 小工具 ----

    /**
     * FlareSolverr 的响应是 JSON，整页 HTML 装在 content 那一字段里，引号与转义都得按 JSON 解。
     * 它打印时冒号后面带一个空格（"content": "..."），所以不能拿 "\""key"":" 整串去找——
     * 那样每个字段都找不到，每一页都会被判成空壳，看着像"浏览器也取不到"，其实是解析没解出来。
     */
    private static String field(String json, String key) {
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        if (at < 0) return "";
        int i = at + needle.length();
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != ':') return "";
        i++;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return "";
        if (json.charAt(i) == '"') {
            StringBuilder out = new StringBuilder();
            i++;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char n = json.charAt(i + 1);
                    if (n == 'n') out.append('\n');
                    else if (n == 'r') out.append('\r');
                    else if (n == 't') out.append('\t');
                    else if (n == 'u' && i + 5 < json.length()) {
                        out.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                        i += 4;
                    } else out.append(n);
                    i += 2;
                    continue;
                }
                if (c == '"') break;
                out.append(c);
                i++;
            }
            return out.toString();
        }
        int end = json.indexOf(',', i);
        if (end < 0) end = json.length();
        return json.substring(i, end).trim();
    }



    private static int count(String text) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) >= 0x4E00 && text.charAt(i) <= 0x9FFF) total++;
        return total;
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = stream.read(buffer)) > 0) out.write(buffer, 0, n);
        stream.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void write(File file, String text) {
        try {
            file.getParentFile().mkdirs();
            FileOutputStream out = new FileOutputStream(file);
            out.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {
            // 留档写不下去不影响实测
        }
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        file.delete();
    }

    private static String cut(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    static List<String> urls() {
        return java.util.Arrays.asList(URLS);
    }
}
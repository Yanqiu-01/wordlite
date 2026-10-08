package com.rikkahub.wordlite;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;

/**
 * 手机渲染取正文的真机探针（不进发布前的必跑清单：要真机、要联网、要对家的页面还在）。
 *
 * 它是装在用户手机里的那台 FlareSolverr 的量台：PageRender 用 WebView 后台加载一页、
 * 等文本静默、取 innerText，PageText 判它是精要、摘要、正文还是那一堵墙，
 * 取回的那一段当场走 CorpusImport 进自建库，再拿它自己的一句话种进一段无关稿子里比对一次，
 * 证明确实"能拿它比对"，而不只是"取回了一段字"。
 *
 * 与本机那一轮（tests/BrowserBodyProbe 跑 FlareSolverr 与 Chrome headless）跑同一批真地址，
 * 两处的数字可以对着看：本机拿到的那 3,331 汉字，手机上的 WebView 能不能拿到。
 *
 * 跑法：pwsh tools/build-pagerender-probe.ps1
 * 单独加地址：adb shell am start -n com.rikkahub.wordlite.pagerender/.PageRenderActivity `
 *              --es url "https://..." （可重复 --es url2、url3）
 * 输出：logcat -s WLpagerender，末尾一行 DONE。
 */
public final class PageRenderActivity extends Activity {
    private static final String TAG = "WLpagerender";

    /** 与 tests/BrowserBodyProbe 同一批真地址：万方详情页、维普详情页、知网 wap 详情页、arXiv 的 HTML 全文。 */
    private static final String[] TARGETS = {
        "https://d.wanfangdata.com.cn/periodical/jsjgcyyy202103005",
        "https://www.cqvip.com/doc/journal/7103817796",
        "https://wap.cnki.net/touch/web/Journal/Article/GWDZ202620023.html",
        "https://ar5iv.labs.arxiv.org/html/2303.08774",
    };

    private static final int PER_PAGE_MILLIS = 30000;

    private Thread worker;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        final ArrayList<String> urls = new ArrayList<String>();
        Intent intent = getIntent();
        for (int i = 0; i < TARGETS.length; i++) urls.add(TARGETS[i]);
        if (intent != null) {
            String[] keys = { "url", "url2", "url3", "url4", "url5" };
            for (int i = 0; i < keys.length; i++) {
                String extra = intent.getStringExtra(keys[i]);
                if (extra != null && extra.trim().length() > 0) urls.add(extra.trim());
            }
            // 离线那一档：把 adb push 进本应用外部目录的页面按 file:// 加载。
            // 用途不是取代真站点的联网测量，而是在"这台手机此刻一个 socket 都开不出去"的机器上，
            // 仍然能把 渲染→取字→分档→入库→命中自检 这一整条链跑完。
            File dir = getExternalFilesDir(null);
            if (intent.getBooleanExtra("scan", false) && dir != null) {
                // 扫整个目录：adb 把多个 --es 传成什么样子不该由测量结果来承担，
                // 推什么进目录就渲染什么，顺序按文件名。
                File[] pages = dir.listFiles();
                java.util.Arrays.sort(pages == null ? new File[0] : pages);
                for (int i = 0; pages != null && i < pages.length; i++) {
                    String lower = pages[i].getName().toLowerCase(java.util.Locale.ROOT);
                    if (!pages[i].isFile() || !lower.endsWith(".html")) continue;
                    urls.add("file://" + pages[i].getAbsolutePath());
                }
                say("扫 " + dir + " 收到 " + (urls.size() - TARGETS.length) + " 个本地页面");
            }
            String[] fileKeys = { "file", "file2", "file3", "file4", "file5", "file6" };
            for (int i = 0; i < fileKeys.length; i++) {
                String name = intent.getStringExtra(fileKeys[i]);
                if (name == null || name.trim().length() == 0) continue;
                File page = new File(dir, name.trim());
                if (!page.isFile()) {
                    say("跳过 " + name + "：外部目录里没有这个文件（adb push 到 " + dir + "）");
                    continue;
                }
                urls.add("file://" + page.getAbsolutePath());
            }
        }
        final ArrayList<String> list = new ArrayList<String>(urls);
        worker = new Thread(new Runnable() {
            public void run() { harvest(list); }
        }, "page-render-probe");
        worker.start();
    }

    private void harvest(ArrayList<String> urls) {
        say("START 手机渲染探针：" + urls.size() + " 个地址，每页最多 " + PER_PAGE_MILLIS + " ms");
        say("UA " + PageText.USER_AGENT);
        say("静默窗口 " + PageText.DEFAULT_SILENCE_MILLIS + " ms，轮询 "
                + PageText.DEFAULT_POLL_MILLIS + " ms，入库门槛：摘要 "
                + PageText.MIN_ABSTRACT_CHINESE_CHARS + " / 精要 " + PageText.MIN_DIGEST_CHINESE_CHARS
                + " / 整页 " + PageText.MIN_BODY_CHINESE_CHARS + " 个汉字");
        outDir = getExternalFilesDir(null);
        File dir = new File(getFilesDir(), "library");
        LocalLibrary library = new LocalLibrary(dir);
        int okPages = 0;
        int stored = 0;
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            PageRender.Options options = new PageRender.Options();
            options.timeoutMillis = PER_PAGE_MILLIS;
            long began = System.currentTimeMillis();
            PageRender.Result result = PageRender.renderBlocking(this, url, options, null);
            report(url, result);
            if (result.ok) okPages++;
            if (result.picked != null && result.picked.ok) {
                if (store(library, result)) stored++;
            }
            say("---- 第 " + (i + 1) + " 页耗时 " + (System.currentTimeMillis() - began) + " ms");
        }
        say("DONE 渲染成功 " + okPages + "/" + urls.size() + " 页，入库 " + stored + " 篇，库里共 "
                + library.size() + " 条");
    }

    /** 一页的读数：形状、字节数、汉字数、轮询次数、取回那一段的档位，外加开头几个字。 */
    private File outDir;

    private void report(String url, PageRender.Result result) {
        File dir = outDir;
        say("URL " + url);
        say("  最终地址 " + result.finalUrl);
        say("  整页 " + result.chars + " 字 / " + result.chinese + " 个汉字，轮询 " + result.polls
                + " 次，" + result.millis + " ms" + (result.timedOut ? "（到时限掐表）" : ""));
        say("  挂载 " + (result.attached ? "已挂上内容视图" : "没挂上（没有 layout）")
                + "，主文档" + (result.pageFinished ? "加载完了" : "没加载完")
                + "，取字回了 " + result.dumps + " 次，轮询 " + result.polls + " 次");
        say("  形状 " + (result.shape.length() == 0 ? "可读" : result.shape)
                + (result.failure.length() == 0 ? "" : "  失败：" + result.failure));
        if (result.title != null && result.title.trim().length() > 0) say("  页面标题 " + result.title);
        if (result.picked == null) {
            say("  没有可入库的一段");
            return;
        }
        say("  取回 " + (result.picked.ok ? result.picked.material : "无档位") + " "
                + result.picked.chinese + " 个汉字，落库名 " + result.picked.name);
        say("  注记 " + result.picked.note);
        say("  开头 " + head(result.picked.ok ? result.picked.text : result.text, 120));
        dump(dir, result);
    }

    /**
     * 把清洗后的整页文本落到外部目录，事后 adb pull 回来看结构。
     * 没有这一份，"认不出摘要那一段"这种失败就只能靠猜——真页面的小节标题写法比想象多。
     */
    private void dump(File dir, PageRender.Result result) {
        if (dir == null || result.text.length() == 0) return;
        try {
            File out = new File(dir, "dump-" + Math.abs(result.url.hashCode()) + ".txt");
            java.io.FileOutputStream stream = new java.io.FileOutputStream(out);
            try {
                stream.write(("URL " + result.url + "\n最终地址 " + result.finalUrl
                        + "\n标题 " + result.title + "\n形状 " + result.shape
                        + "\n整页 " + result.chars + " 字 / " + result.chinese + " 个汉字\n\n").getBytes("UTF-8"));
                stream.write(result.text.getBytes("UTF-8"));
            } finally {
                stream.close();
            }
            say("  整页文本落盘 " + out.getName());
        } catch (Throwable error) {
            say("  整页文本落盘失败 " + error);
        }
    }

    /** 取回的那一段当场入库，并拿它自己的一句话验一次"查重真的能拿它比对"。 */
    private boolean store(LocalLibrary library, PageRender.Result result) {
        PageText.Picked picked = result.picked;
        ArrayList<CorpusImport.Source> sources = new ArrayList<CorpusImport.Source>();
        CorpusImport.Source source = new CorpusImport.Source(picked.name,
                bytes(picked.text));
        source.material = picked.material;
        sources.add(source);
        CorpusImport.Batch batch = CorpusImport.run(library, sources, null);
        if (batch.receipts.isEmpty()) {
            say("  入库 没有回执");
            return false;
        }
        CorpusImport.Receipt receipt = batch.receipts.get(0);
        say("  入库 " + receipt.describe());
        if (!receipt.imported()) return false;
        LocalLibrary reopened = new LocalLibrary(new File(getFilesDir(), "library"));
        TextCorpus corpus = new TextCorpus();
        reopened.index(corpus);
        String planted = firstSentence(picked.text);
        if (planted.length() == 0) {
            say("  自检 取回的那一段里没有一句完整的话，不做命中自检");
            return true;
        }
        String draft = DECOY + planted + DECOY;
        TextCorpus.Report matched = corpus.match(draft, null);
        SourceLedger ledger = SourceLedger.aggregate(matched.hits, TextCorpus.normalize(draft),
                matched.comparedChars);
        String tier = ledger.rows.isEmpty() ? "无" : SourceLedger.materialLabel(ledger.rows.get(0));
        say("  自检 抄了取回那一段的第一句：" + matched.hits.size() + " 处命中 / "
                + matched.duplicateChars + " 字 / 分母 " + matched.comparedChars
                + " 字，来源档 " + tier + "，正文级 " + (ledger.rows.isEmpty() ? 0
                        : ledger.rows.get(0).fullChars) + " 字，精要级 "
                + (ledger.rows.isEmpty() ? 0 : ledger.rows.get(0).digestChars) + " 字，摘要级 "
                + (ledger.rows.isEmpty() ? 0 : ledger.rows.get(0).abstractChars) + " 字");
        say("  自检 比对材料：" + CorpusLedger.aggregate(corpus).summaryLine());
        return true;
    }

    private static final String DECOY = "本研究的现场部分集中在老城区排水管网改造。施工前的普查发现，"
            + "管段接错与淤积同时存在，水力模型的率定因此分成两步，第一步以旱季流量校核管段阻力系数。";

    private static String firstSentence(String text) {
        String value = text == null ? "" : text;
        int from = 0;
        while (from < value.length()) {
            int end = -1;
            for (int i = from; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '。' || c == '！' || c == '？') { end = i; break; }
            }
            if (end < 0) return "";
            String sentence = value.substring(from, end + 1).trim();
            if (PageText.chineseChars(sentence) >= 40) return sentence;
            from = end + 1;
        }
        return "";
    }

    private static byte[] bytes(String text) {
        try {
            return text.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException error) {
            return new byte[0];
        }
    }

    private static String head(String text, int max) {
        String value = text == null ? "" : text.replace('\n', ' ').trim();
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static void say(String line) {
        Log.i(TAG, line);
    }
}
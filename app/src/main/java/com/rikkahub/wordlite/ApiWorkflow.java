package com.rikkahub.wordlite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Review-ribbon actions, explicit network jobs and manual acceptance of each suggestion. */
public final class ApiWorkflow {
    public interface Host {
        DocxDocument document();
        void sync();
        DocxDocument.ParagraphBlock currentParagraph();
        int[] selectedRange();
        Uri source();
        String fileName();
        void busy(boolean value);
        void beforeRewrite(int paragraph);
        void changed();
        void navigate(int paragraph, int start, int end);
        /** Ask the shell for the document picker that feeds the local comparison library. */
        void pickLibrary();
    }
    public static final int REQUEST_REPORT = 74;
    private final Activity activity;
    private final Host host;
    private final SettingsManager settings;
    private ApiClient.Task job;
    private Thread worker;
    private AlertDialog progressDialog;
    private boolean destroyed;
    private ApiResult.Check lastCheck;
    private TextSelection lastSubmitted;
    private String pendingReport;
    private final ArrayList<RewriteTask> rewrites = new ArrayList<RewriteTask>();
    private ApiConfig rewriteConfig;
    private int rewriteCursor;
    private final int ink, surface;
    private static final int DUPLICATE_INK = 0x66FFB74D;
    private static final int MAX_LIBRARY_FILE = 16 * 1024 * 1024;
    private static final int AIGC_INK = 0x664FC3F7;
    private LocalLibrary library;
    /** 报告中心的数据层：跑完一次检查落一条记录，回看走磁盘，不重跑比对。 */
    private ReportStore reports;
    private EngineSettings engine = new EngineSettings();
    private DuplicateEngine.Report lastScan;
    private String preRewriteText = "";
    private TextSelection lastScanned;
    private boolean scanShowsDuplicates = true;
    /** 这一轮用户要的是联网查重，但检索设置里联网是关着的——结果页要认这件事。 */
    private boolean webAskedOff;
    private TextView jobLabel;

    public ApiWorkflow(Activity activity, Host host) {
        this.activity = activity; this.host = host; settings = new SettingsManager(activity);
        boolean dark = (activity.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        ink = dark ? 0xFFF2F2F2 : 0xFF202020; surface = dark ? 0xFF252525 : 0xFFF7F7F7;
        library = new LocalLibrary(new File(activity.getFilesDir(), "wordlite-library"));
        reports = new ReportStore(new File(activity.getFilesDir(), "wordlite-reports"));
        try { engine = settings.loadEngine(); } catch (Exception ignored) { engine = new EngineSettings(); }
    }
    public void settings(ApiConfig.Service service) {
        Intent intent = new Intent(activity, ApiSettingsActivity.class); intent.putExtra("service", service.ordinal()); activity.startActivity(intent);
    }
    private ApiConfig config(ApiConfig.Service service) {
        try {
            ApiConfig config = settings.load(service);
            if (config.url.isEmpty()) { settings(service); return null; }
            config.validate(); return config;
        } catch (Exception error) { toast("设置读取失败"); return null; }
    }
    public void checkMenu() {
        if (job != null || host.document() == null) return;
        final ArrayList<String> ids = new ArrayList<String>();
        final ArrayList<String> titles = new ArrayList<String>();
        /* 一次点到底：自建库和联网检索一起跑，不再让用户先选一种再补另一种。
           以前"本机查重（自建库）"和"联网查重（内置文献库）"是两格，看着像两套判据——
           其实联网那一格本来也会把自建库index进去，分开摆只是把人绕晕。离线那一格留着，
           名字里写清它比的是什么：飞机上、没有信号的地方，它照样能出数。 */
        titles.add("查重（自建库 " + library.size() + " 篇 + 联网检索）"); ids.add("scan");
        titles.add("只查自建库（不联网）"); ids.add("local");
        titles.add("AIGC 检测"); ids.add("aigc");
        titles.add("自建库（" + library.size() + " 篇）"); ids.add("library");
        titles.add("检索设置"); ids.add("engines");
        titles.add("检索自检（文献库通不通）"); ids.add("probe");
        titles.add("自定义接口查重"); ids.add("api");
        titles.add("报告中心（历史报告）"); ids.add("center");
        if (lastScan != null) {
            titles.add("查重结果"); ids.add("result");
            titles.add("导出报告"); ids.add("export");
            titles.add("清除高亮"); ids.add("clear");
        }
        new AlertDialog.Builder(activity).setTitle("查重")
                .setItems(titles.toArray(new String[titles.size()]), (dialog, which) -> {
            String id = ids.get(which);
            if ("scan".equals(id)) scan(true, false);
            else if ("local".equals(id)) scan(false, false);
            else if ("aigc".equals(id)) scan(false, true);
            else if ("library".equals(id)) libraryMenu();
            else if ("engines".equals(id)) engineSettings();
            else if ("probe".equals(id)) engineProbe();
            else if ("api".equals(id)) customCheckMenu();
            else if ("center".equals(id)) reportCenter();
            else if ("result".equals(id)) showScan();
            else if ("export".equals(id)) scanReport();
            else { host.document().displayHighlights.clear(); host.changed(); }
        }).setNegativeButton("取消", null).show();
    }
    /** The user-configured checker endpoint, unchanged from earlier releases. */
    /** Bottom-bar shortcut: AIGC tendency only, no comparison against any corpus. */
    public void aigc() {
        if (job != null || host.document() == null) return;
        scan(false, true);
    }
    private void customCheckMenu() {
        String[] actions = lastCheck == null ? new String[]{"上传文档", "选中文本"}
                : new String[]{"上传文档", "选中文本", "查重结果", "导出报告", "清除高亮"};
        new AlertDialog.Builder(activity).setTitle("自定义接口查重").setItems(actions, (dialog, which) -> {
            if (which < 2) check(which == 0);
            else if (which == 2) showCheck();
            else if (which == 3) report();
            else { host.document().displayHighlights.clear(); host.changed(); }
        }).setNegativeButton("取消", null).show();
    }
    private void check(boolean upload) {
        final ApiConfig config = config(ApiConfig.Service.CHECK); if (config == null) return;
        host.sync(); final TextSelection submitted;
        try {
            if (upload) submitted = TextSelection.all(host.document());
            else { int[] range = host.selectedRange(); submitted = TextSelection.paragraph(host.currentParagraph(), range[0], range[1]); }
            if (submitted.text.trim().isEmpty()) throw new IllegalArgumentException("没有选中内容");
        } catch (IllegalArgumentException error) { toast(error.getMessage()); return; }
        final DocxDocument document = host.document(); final ApiClient.Task task = begin("查重");
        worker = new Thread(() -> {
            try {
                byte[] file = null;
                if (upload) {
                    if (config.mode != ApiConfig.Mode.MULTIPART) throw new ApiClient.Failure("文档上传请求类型无效", 0);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream() {
                        @Override public synchronized void write(byte[] buffer, int offset, int count) {
                            if (size() > ApiClient.MAX_UPLOAD - count) throw new IllegalArgumentException("文档过大");
                            super.write(buffer, offset, count);
                        }
                        @Override public synchronized void write(int value) {
                            if (size() >= ApiClient.MAX_UPLOAD) throw new IllegalArgumentException("文档过大"); super.write(value);
                        }
                    };
                    try (InputStream input = activity.getContentResolver().openInputStream(host.source())) {
                        if (input == null) throw new ApiClient.Failure("文档读取失败", 0);
                        DocxWriter.write(input, bytes, document); file = bytes.toByteArray();
                    }
                }
                ApiClient.Response response = ApiClient.execute(config, submitted.text, file, host.fileName(), task);
                final ApiResult.Check result = ApiResult.check(config, response, submitted.text);
                complete(task, () -> {
                    lastCheck = result; lastSubmitted = submitted;
                    applyHighlights(result, submitted); showCheck();
                });
            } catch (Exception error) { fail(task, error); }
        }, "word-api-check"); worker.start();
    }
    private void applyHighlights(ApiResult.Check result, TextSelection submitted) {
        DocxDocument document = host.document(); document.displayHighlights.clear();
        if (!submitted.unchanged(document)) return;
        for (ApiResult.Fragment fragment : result.fragments)
            for (TextSelection.Range range : submitted.ranges(fragment.start, fragment.end)) {
                DocxDocument.DisplayHighlight highlight = new DocxDocument.DisplayHighlight();
                highlight.paragraphIndex = range.paragraphIndex; highlight.start = range.start; highlight.end = range.end;
                document.displayHighlights.add(highlight);
            }
        host.changed();
    }
    private void showCheck() {
        if (lastCheck == null) return;
        LinearLayout box = column(); box.setPadding(dp(16), dp(8), dp(16), dp(8));
        box.addView(label(String.format(Locale.CHINA, "重复率 %.2f%%", lastCheck.rate), 20));
        box.addView(label(lastCheck.detectedAt, 12));
        for (ApiResult.Fragment fragment : lastCheck.fragments) {
            TextView snippet = label(fragment.text + "\n" + fragment.source, 14);
            snippet.setPadding(0, dp(10), 0, dp(10)); snippet.setTextIsSelectable(true);
            snippet.setOnClickListener(view -> {
                if (!lastSubmitted.unchanged(host.document())) return;
                ArrayList<TextSelection.Range> ranges = lastSubmitted.ranges(fragment.start, fragment.end);
                if (!ranges.isEmpty()) { TextSelection.Range range = ranges.get(0); host.navigate(range.paragraphIndex, range.start, range.end); }
            });
            box.addView(snippet);
        }
        ScrollView scroll = new ScrollView(activity); scroll.addView(box);
        new AlertDialog.Builder(activity).setTitle("查重结果").setView(scroll).setNegativeButton("关闭", null)
                .setPositiveButton("导出报告", (dialog, which) -> report()).show();
    }
    private void report() {
        if (lastCheck == null) return;
        pendingReport = CheckReport.html(host.fileName(), lastCheck);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/html"); intent.putExtra(Intent.EXTRA_TITLE, host.fileName().replaceAll("(?i)\\.docx$", "") + "-查重报告.html");
        activity.startActivityForResult(intent, REQUEST_REPORT);
    }
    public boolean activityResult(int request, int result, Intent data) {
        if (request != REQUEST_REPORT) return false;
        final String content = pendingReport; pendingReport = null;
        if (result != Activity.RESULT_OK || content == null || data == null || data.getData() == null) return true;
        final Uri target = data.getData();
        if (target.equals(host.source())) { toast("输出路径无效"); return true; }
        new Thread(() -> {
            String message = "报告已导出";
            try (OutputStream output = activity.getContentResolver().openOutputStream(target, "w")) {
                if (output == null) throw new java.io.IOException(); output.write(content.getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) { message = "报告导出失败"; }
            final String resultMessage = message; activity.runOnUiThread(() -> { if (alive()) toast(resultMessage); });
        }, "word-check-report").start(); return true;
    }
    /** Local library only, or the library plus the built-in literature sources. */
    private void scan(final boolean useWeb, final boolean aigcOnly) {
        host.sync();
        final TextSelection selection;
        try {
            selection = TextSelection.all(host.document());
            if (selection.text.trim().isEmpty()) throw new IllegalArgumentException("文档没有正文内容");
        } catch (IllegalArgumentException error) { toast(error.getMessage()); return; }
        final DocxDocument document = host.document();
        // 随包的离线 AIGC 模型在查重线程外装载一次；不达标时 engaged() 为 false，一个百分比都不印。
        AigcNgramModel.loadFromAssets(activity);
        // 随包的 Adobe-GB1 表也装载一次：方正那一族期刊 PDF 的码到字全靠它，缺表时那一路字按读不出算。
        CidUnicodeTables.loadFromAssets(activity);
        final ApiClient.Task task = begin(aigcOnly ? "AIGC 检测"
                : useWeb ? "查重（自建库 + 联网）" : "查重（只查自建库）");
        final EngineSettings options = engine.copy();
        final boolean wantWeb = useWeb && options.web && !aigcOnly;
        /* 点了合并那一格、可 检索设置 里"联网"是关着的：这一轮实际只比了自建库。
           这事必须写在结果页上——不然用户以为查过了全网。 */
        webAskedOff = useWeb && !options.web && !aigcOnly;
        worker = new Thread(() -> {
            try {
                TextCorpus corpus = new TextCorpus();
                if (!aigcOnly) library.index(corpus);
                PaperSources.Limits limits = new PaperSources.Limits();
                limits.timeoutSeconds = options.timeoutSeconds;
                limits.perEngine = options.perEngine;
                /* 设置里的窗口数终于到得了检索循环：它是"每个源最多被问几次"，
                   不再是 PaperSources.Limits 里一个没人读的摆设。 */
                limits.windows = options.windows;
                limits.fullTexts = DuplicateEngine.MAX_FULL_TEXTS;
                /* 检索设置里那个开关到得了检索循环：开着就用剩下的请求额度顺手抓几篇开放获取正文，
                   并按正文比对、落进自建库；关掉（或设成 0）就还是只在名次队那几篇上花全文额度。 */
                limits.autoFullTexts = options.autoPdf ? options.autoPdfs : 0;
                limits.coreKey = options.coreKey;
                limits.proxy = options.proxy;
                limits.solver = options.solver;
                ArrayList<String> engines = new ArrayList<String>();
                if (wantWeb) for (String id : options.engines) engines.add(id);
                final DuplicateEngine.Report result = DuplicateEngine.scan(selection, corpus, wantWeb,
                        engines, limits, task, new DuplicateEngine.Progress() {
                            public void step(String label, int done, int total) {
                                progress(total > 0 ? label + " " + done + "/" + total : label);
                            }
                        });
                /* 顺手抓回的开放获取正文此刻才落进自建库：这一轮的比对已经用内存里那份正文跑完，
                   入库是为了下一轮不必再花一次请求重抓同一篇。存了几篇、几篇库里已有、几篇没存进去
                   都写进注记，再落报告中心——顺序反了，回看的那份报告里就没有这一句。 */
                DuplicateEngine.fileAutoBodies(library, result);
                /* 报告中心（1.0.0）：先落一条记录再回界面。写盘留在 worker 线程里做——
                   几十万字节的 JSON 压在 UI 线程上，取消按钮会先卡住。 */
                final ReportStore.Record saved = reports.save(ReportStore.recordFor(result, host.fileName()));
                complete(task, () -> {
                    lastScan = result; lastScanned = selection; scanShowsDuplicates = !aigcOnly;
                    if (document == host.document()) applyScan(result, selection);
                    // 存不下必须当场说：结果页此刻就在屏幕上，静默丢记录等于让用户以为回看得到，点开列表却是空的。
                    if (saved == null) toast("结果已出，但这次没能存进报告中心：" + reports.lastError());
                    showScan();
                });
            } catch (Exception error) { fail(task, error); }
        }, "wordlite-scan"); worker.start();
    }
    /** 覆盖率那一行的原文；注记与面板同源，两处不许说法不一。 */
    private static String coverageLine(DuplicateEngine.Report result) {
        String line = "检索覆盖 " + result.windowsRetrieved + "/" + result.windowsAvailable + " 窗口";
        line = line + " · " + result.coveredChars + "/" + result.comparableChars + " 字";
        if (result.retrievalPartial) line = line + " · 相似率是下限";
        return line;
    }
    private void applyScan(DuplicateEngine.Report result, TextSelection selection) {
        DocxDocument document = host.document();
        document.displayHighlights.clear();
        if (!selection.unchanged(document)) return;
        if (scanShowsDuplicates)
            for (TextCorpus.Hit hit : result.hits) addHighlight(selection, hit.start, hit.end, DUPLICATE_INK);
        /* 高亮涂的是可疑区间（0.7.1），不再是单句：实测真人论文里 0.45 档的高分句 100% 是孤立的
           （≥2 连句的字符占比 0.0%），机写骨架稿同档 4.5% 且 5/70 段成片——涂句子会把人自己的
           孤立长句涂一片，涂区间才是"这一片都一个腔"。门槛与指标区那一格同一个常量。 */
        if (result.aigc != null) for (AigcDetector.Segment segment : result.aigc.segments)
            if (segment.flagged) addHighlight(selection, segment.start, segment.end, AIGC_INK);
        host.changed();
    }
    private void addHighlight(TextSelection selection, int start, int end, int color) {
        for (TextSelection.Range range : selection.ranges(start, end)) {
            DocxDocument.DisplayHighlight highlight = new DocxDocument.DisplayHighlight();
            highlight.paragraphIndex = range.paragraphIndex;
            highlight.start = range.start; highlight.end = range.end; highlight.color = color;
            host.document().displayHighlights.add(highlight);
        }
    }
    private void showScan() {
        final DuplicateEngine.Report result = lastScan;
        if (result == null || !alive()) return;
        LinearLayout box = column(); box.setPadding(dp(16), dp(8), dp(16), dp(8));
        if (scanShowsDuplicates && !result.retrievalIncomplete) {
            box.addView(label(String.format(Locale.CHINA, "总相似度比 %.2f%%", result.overallRate), 20));
            if (webAskedOff)
                box.addView(label("检索设置里\"联网检索\"是关着的：上面这个数只比了自建库的 "
                        + result.localDocuments + " 篇材料，没有查过文献库。", 12));
            if (result.windowsPlanned > 0) box.addView(label(coverageLine(result), 12));
            box.addView(label(String.format(Locale.CHINA, "去除引用重复比 %.2f%%  ·  自编率 %.2f%%",
                    result.excludingCitationsRate, result.selfWrittenRate), 13));
            if (!result.byEngine.isEmpty()) {
                StringBuilder line = new StringBuilder("来源分布：");
                for (Map.Entry<String, Double> entry : result.byEngine.entrySet())
                    line.append(engineTitle(entry.getKey())).append(' ')
                            .append(String.format(Locale.CHINA, "%.1f%%  ", entry.getValue()));
                box.addView(label(line.toString().trim(), 12));
            }
            /* 比了哪些库、各多少篇，紧跟在三个比率下面：一个总相似度比没说它是拿什么比出来的，
               读者就会把"拿三十条摘要比出来的 3%"读成"跟全网正文比过只有 3%"。 */
            String inventory = DuplicateEngine.inventoryLine(result);
            if (!inventory.isEmpty()) box.addView(label(inventory, 12));
            String split = DuplicateEngine.materialSplitLine(result);
            if (!split.isEmpty())
                box.addView(label(DuplicateEngine.MATERIAL_SPLIT_LABEL + "：" + split, 12));
            if (result.retrievalPartial) box.addView(label(result.retrievalPartialReason, 11));
            // 摘要层另起一行，名字里自带"近似"与"只到摘要"：它不是第四个比率，也不与上面三个相加。
            String abstractLine = DuplicateEngine.abstractLayerLine(result);
            if (!abstractLine.isEmpty())
                box.addView(label(DuplicateEngine.ABSTRACT_LAYER_LABEL + " " + abstractLine, 12));
        } else if (result.retrievalIncomplete) {
            // A run that consulted nothing must not read as a clean document: 0.00% here would be
            // the most expensive number in the app to get wrong.
            box.addView(label("未完成查重", 20));
            /* 手机自己的网络事实排在原因前面。检索站不通不等于手机没网，
               把两件事混成一句，用户就会去开关飞行模式，而那正是没用的一步。 */
            String phoneNetwork = NetworkStatus.line(activity);
            if (!phoneNetwork.isEmpty()) box.addView(label(phoneNetwork, 13));
            String blockage = result.retrievalReason == null
                    ? "检索没有取回可比对的文献，相似度类指标无法成立" : result.retrievalReason;
            TextView blockageLine = label(blockage, 13);
            /* 卡在出口那一档，这一屏只有这一句救得回来：加粗，别被下面那几行说明挤下去。 */
            if (DuplicateEngine.noNetworkExit(result)) blockageLine.setTypeface(Typeface.DEFAULT_BOLD);
            box.addView(blockageLine);
            box.addView(label("相似度类指标不成立，只有 AIGC 倾向是本机计算的结果。", 11));
            // 这一屏更要说清比了什么：库里有几篇、各是什么档，直接决定"未完成"这三个字该怎么读。
            String inventory = DuplicateEngine.inventoryLine(result);
            if (!inventory.isEmpty()) box.addView(label(inventory, 12));
            /* 这一屏不许停在"量不到"就完事：上面那句讲的是真的（没有可比正文），
               但同一屏必须跟着给出摘要层那个数，以及现在就能走的下一步。 */
            String abstractLine = DuplicateEngine.abstractLayerLine(result);
            if (!abstractLine.isEmpty()) {
                box.addView(label(DuplicateEngine.ABSTRACT_LAYER_LABEL + " " + abstractLine, 13));
                box.addView(label(DuplicateEngine.abstractLayerNextSteps(), 11));
            }
        }
        /* 这一格与 HTML 报告同一份文案、同一把尺子（0.7.1）：档位 + 可疑字数 + 全文字数，没有百分号。
           0.7.0 这里印的 "AIGC 倾向 17.50%" 是字符加权句分冒充比例，与上一行的总相似度比不是一把尺。 */
        box.addView(label("机器生成倾向 " + DuplicateEngine.aigcTrend(result), 15));
        String aigcScore = DuplicateEngine.aigcScoreLine(result);
        if (!aigcScore.isEmpty())
            box.addView(label(DuplicateEngine.AIGC_SCORE_LABEL + " " + aigcScore, 11));
        box.addView(label(result.detectedAt + "  ·  用时 " + (result.elapsedMillis / 1000L) + " 秒", 11));
        for (String note : result.notes) box.addView(label("提示：" + note, 11));
        downloadables(box, result);
        /* 降重这一格只在真的判出重复之后出现。名字里写清它和在线降重的区别：改完要拿同一把尺子
           再量一次整篇，整篇命中字数没变小就退回原文——"改写过了"不等于"降下来了"。 */
        if (scanShowsDuplicates && !result.hits.isEmpty()) {
            TextView fix = label("降重并复核：只改判为重复的段落，改完用同一把尺子再量整篇（"
                    + result.hits.size() + " 处命中）", 14);
            fix.setTypeface(Typeface.DEFAULT_BOLD);
            fix.setPadding(0, dp(10), 0, dp(10));
            fix.setOnClickListener(view -> rewriteVerified());
            box.addView(fix);
        }
        if (scanShowsDuplicates) for (final TextCorpus.Hit hit : result.hits) {
            TextView snippet = label(snippetText(hit), 14);
            snippet.setPadding(0, dp(10), 0, dp(10)); snippet.setTextIsSelectable(true);
            snippet.setOnClickListener(view -> navigateTo(hit.start, hit.end));
            box.addView(snippet);
        }
        if (result.aigc != null) {
            boolean scored = AigcScorer.calibrated();
            box.addView(label(scored ? "AIGC 倾向较高的句子"
                    : "触发过判据的句子（判据未标定，这里按原文顺序列，不是可疑度排序）", 13));
            int shown = 0;
            for (final AigcDetector.Sentence sentence : result.aigc.sentences) {
                if (shown >= 20 || sentence.score < 0.4f) continue;
                shown++;
                String quote = lastScanned == null ? "" : safeSlice(lastScanned.text, sentence.start, sentence.end);
                // 未标定时行首那个 "35%" 一并撤掉：它是句分冒充百分比，方向还是反的。
                TextView row = label((scored ? String.format(Locale.CHINA, "%.0f%%  ", sentence.score * 100f) : "")
                        + quote + "\n" + join(sentence.features), 13);
                row.setPadding(0, dp(8), 0, dp(8)); row.setTextIsSelectable(true);
                row.setOnClickListener(view -> navigateTo(sentence.start, sentence.end));
                box.addView(row);
            }
        }
        ScrollView scroll = new ScrollView(activity); scroll.addView(box);
        new AlertDialog.Builder(activity).setTitle("查重结果").setView(scroll).setNegativeButton("关闭", null)
                .setPositiveButton("导出报告", (dialog, which) -> scanReport()).show();
    }
    /**
     * 结果页的"下进自建库"那一格：只列现在**只有摘要可比**、但检索源挂着可下载 PDF 的候选。
     * 点一下下载 + 走 CorpusImport 进自建库——自建库与联网正文进的是同一个语料、同一条判据，
     * 所以这一条不是装饰：它把这 N 篇从"摘要级"抬到"正文级"。
     * <p>第一行是"全部下进自建库"：一次点十下不是批量，CorpusImport.download 本来就吃一批 Pick，
     * 缺的只是把整批递进去、并把逐篇回执显示出来的这一步。
     */
    private void downloadables(LinearLayout box, DuplicateEngine.Report result) {
        if (result == null || result.downloadables.isEmpty()) return;
        final ArrayList<DuplicateEngine.Downloadable> picks =
                new ArrayList<DuplicateEngine.Downloadable>(result.downloadables);
        box.addView(label("可以下进自建库的开放获取全文 " + picks.size()
                + " 篇（现在只有摘要可比；下进来后按正文比对）", 13));
        TextView every = label("全部下进自建库：" + picks.size() + " 篇（逐篇回执，一份下不成不拖垮其余）", 14);
        every.setTypeface(Typeface.DEFAULT_BOLD);
        every.setPadding(0, dp(10), 0, dp(10));
        every.setOnClickListener(view -> confirmDownload(picks));
        box.addView(every);
        for (final DuplicateEngine.Downloadable pick : picks) {
            TextView row = label("只下这一篇：" + pick.title + "\n" + engineTitle(pick.engine)
                    + " · " + PaperSources.linkLabel(pick.url), 13);
            row.setPadding(0, dp(10), 0, dp(10));
            row.setOnClickListener(view -> confirmDownload(
                    new ArrayList<DuplicateEngine.Downloadable>(java.util.Collections.singletonList(pick))));
            box.addView(row);
        }
    }

    /** 下载之前先说清要花多少流量：单份开放获取 PDF 实测 0.7-3.9 MB，一份的上限是 6 MB。 */
    private void confirmDownload(final ArrayList<DuplicateEngine.Downloadable> picks) {
        if (job != null) { toast("有任务在跑，稍后再试"); return; }
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < picks.size() && i < 4; i++) {
            if (names.length() > 0) names.append("\n");
            names.append("· ").append(cut(picks.get(i).title, 40));
        }
        if (picks.size() > 4) names.append("\n… 另 ").append(picks.size() - 4).append(" 篇");
        new AlertDialog.Builder(activity).setTitle("下进自建库")
                .setMessage("共 " + picks.size() + " 篇，逐篇下载并导入自建库：\n" + names
                        + "\n\n单份 PDF 实测 0.7-3.9 MB（每份上限 6 MB），这一轮最多约 "
                        + (picks.size() * 6) + " MB。导入后这些篇在下一次查重里按正文比对。")
                .setPositiveButton("下载", (dialog, which) -> importFromUrls(picks))
                .setNegativeButton("取消", null).show();
    }

    /**
     * 下载一批开放获取 PDF 并导入自建库。一个坏链接不许拖垮整批（CorpusImport 那三条规矩），
     * 结果按逐篇回执显示——只看第一条回执等于把后面九篇的下场藏起来。
     */
    private void importFromUrls(final ArrayList<DuplicateEngine.Downloadable> picks) {
        if (job != null) { toast("有任务在跑，稍后再试"); return; }
        // 导入那一步也要这张表：PDF 是在 CorpusImport 里解析的，没跑过查重时它可能还没装载。
        CidUnicodeTables.loadFromAssets(activity);
        final ApiClient.Task task = begin("下载 " + picks.size() + " 篇开放获取全文");
        final EngineSettings options = engine.copy();
        worker = new Thread(() -> {
            String title, body;
            try {
                PaperSources.Limits limits = new PaperSources.Limits();
                limits.timeoutSeconds = options.timeoutSeconds;
                limits.proxy = options.proxy;
                limits.solver = options.solver;
                ArrayList<CorpusImport.Pick> items = new ArrayList<CorpusImport.Pick>();
                for (int i = 0; i < picks.size(); i++)
                    items.add(new CorpusImport.Pick(picks.get(i).title, picks.get(i).url));
                CorpusImport.Batch batch = CorpusImport.download(library, items,
                        url -> PaperSources.downloadPdf(url, limits, task), null, task::cancelled);
                title = batch.summary();
                body = batch.detail();
            } catch (Exception error) {
                title = "下载失败";
                body = error.getMessage() == null || error.getMessage().isEmpty()
                        ? "未知错误" : error.getMessage();
            }
            final String head = title, lines = body;
            complete(task, () -> showImportReceipts(head, lines));
        }, "wordlite-corpus-download");
        worker.start();
    }

    /** 逐篇回执的弹窗：一行一篇，字可选可复制，长名字换行也不挡下一行。 */
    private void showImportReceipts(String title, String lines) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(18), dp(6));
        box.addView(label(title, 13));
        TextView detail = label(lines, 12);
        detail.setTextIsSelectable(true);
        detail.setPadding(0, dp(10), 0, 0);
        box.addView(detail);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        new AlertDialog.Builder(activity).setTitle("下进自建库").setView(scroll)
                .setNegativeButton("关闭", null).show();
    }

    private String snippetText(TextCorpus.Hit hit) {
        TextCorpus.Source source = hit.source;
        String title = source == null || source.title.isEmpty() ? "相似片段" : source.title;
        String year = source == null ? "" : source.year;
        /* 档位跟着出处走：只有摘要可比的那几处必须看得见"正文没比过"这件事，
           否则一句撞进摘要的话会被读成"整篇都比过而且抄了这么多"。 */
        String tier = source != null && DuplicateEngine.MATERIAL_ABSTRACT.equals(source.material)
                ? " · 只有摘要可比" : "";
        return safeSlice(lastScanned == null ? "" : lastScanned.text, hit.start, hit.end)
                + "\n" + title + (year.isEmpty() ? "" : "（" + year + "）") + tier;
    }
    /** 题名在确认框里只占一行，长了截断加省略号；一个字都不编，只是不让它把对话框撑破。 */
    private static String cut(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String safeSlice(String text, int start, int end) {
        if (text == null || start < 0 || end <= start || end > text.length()) return "";
        String value = text.substring(start, end);
        return value.length() <= 160 ? value : value.substring(0, 160) + "…";
    }
    private static String join(ArrayList<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) { if (out.length() > 0) out.append('、'); out.append(value); }
        return out.toString();
    }
    private void navigateTo(int start, int end) {
        TextSelection selection = lastScanned;
        if (selection == null || !selection.unchanged(host.document())) return;
        ArrayList<TextSelection.Range> ranges = selection.ranges(start, end);
        if (ranges.isEmpty()) return;
        TextSelection.Range range = ranges.get(0);
        host.navigate(range.paragraphIndex, range.start, range.end);
    }
    private void scanReport() {
        if (lastScan == null) return;
        chooseTarget(CheckReport.html(host.fileName(), lastScan), host.fileName());
    }
    /** 详情页的导出用的就是记录里存着的那份 HTML，不重跑比对再拼一张。 */
    private void exportRecord(ReportStore.Record record) {
        if (record == null || record.html.length() == 0) { toast("这条报告没有可导出的 HTML"); return; }
        chooseTarget(record.html, record.fileName);
    }
    /** 选存报告的目标只此一处：两个入口将来改格式，不会走成两条路。 */
    private void chooseTarget(String html, String fileName) {
        pendingReport = html;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/html"); intent.putExtra(Intent.EXTRA_TITLE,
                fileName.replaceAll("(?i)\\.docx$", "") + "-查重报告.html");
        activity.startActivityForResult(intent, REQUEST_REPORT);
    }
    /** 报告中心：历史列表 + 详情页。列表只读索引摘要，进详情才读那一个文件。 */
    public void reportCenter() {
        ReportCenterUI center = new ReportCenterUI(activity);
        center.bind(reports, new ReportCenterUI.Listener() {
            public void jumpTo(ReportStore.Record record, ReportStore.Evidence hit) { jumpToHit(record, hit); }
            public void export(ReportStore.Record record) { exportRecord(record); }
        });
        new AlertDialog.Builder(activity).setView(center).setNegativeButton("关闭", null).show();
    }
    /**
     * 从详情页跳回正文。记录里的区间是当时那篇正文上的字符偏移，所以只有「现在这篇就是当时
     * 检的那篇」时才敢跳——拿存下来的长度与指纹验一遍，验不过就直说，不猜位置。
     */
    private void jumpToHit(ReportStore.Record record, ReportStore.Evidence hit) {
        if (record == null || hit == null || host.document() == null) return;
        TextSelection selection = TextSelection.all(host.document());
        if (selection.text.length() != record.sourceChars
                || ReportStore.digest(selection.text) != record.textDigest) {
            toast("正文与检测时已不同，无法定位那段文字");
            return;
        }
        ArrayList<TextSelection.Range> ranges = selection.ranges(hit.start, hit.end);
        if (ranges.isEmpty()) { toast("这段命中的位置在正文里找不到了"); return; }
        TextSelection.Range range = ranges.get(0);
        host.navigate(range.paragraphIndex, range.start, range.end);
    }
    private void libraryMenu() {
        new AlertDialog.Builder(activity).setTitle("自建库")
                .setItems(new String[]{"导入文档", "按知网文献号入库", "查看清单", "清空自建库"}, (dialog, which) -> {
                    if (which == 0) host.pickLibrary();
                    else if (which == 1) cnkiImport();
                    else if (which == 2) showLibrary();
                    else new AlertDialog.Builder(activity).setTitle("清空自建库")
                            .setMessage("将删除本机保存的全部比对文档，文档本身不受影响。")
                            .setPositiveButton("清空", (d, w) -> { library.clear(); toast("自建库已清空"); })
                            .setNegativeButton("取消", null).show();
                }).setNegativeButton("取消", null).show();
    }
    private void showLibrary() {
        final ArrayList<LocalLibrary.Entry> entries = library.entries();
        LinearLayout box = column(); box.setPadding(dp(16), dp(8), dp(16), dp(8));
        if (entries.isEmpty()) box.addView(label("还没有导入文档。导入论文合集或参考文献即可离线查重。", 14));
        for (final LocalLibrary.Entry entry : entries) {
            TextView row = label(entry.name + "\n" + entry.sentences + " 句", 14);
            row.setPadding(0, dp(10), 0, dp(10));
            row.setOnClickListener(view -> new AlertDialog.Builder(activity).setTitle(entry.name)
                    .setItems(new String[]{"从自建库删除"}, (d, w) -> {
                        library.remove(entry.name); toast("已删除");
                    }).setNegativeButton("取消", null).show());
            box.addView(row);
        }
        ScrollView scroll = new ScrollView(activity); scroll.addView(box);
        new AlertDialog.Builder(activity).setTitle("自建库（" + entries.size() + " 篇）")
                .setView(scroll).setNegativeButton("关闭", null)
                .setPositiveButton("导入文档", (dialog, which) -> host.pickLibrary()).show();
    }
    /** SAF result from the library picker; reading happens off the UI thread. */
    public void libraryImport(final ArrayList<Uri> uris) {
        if (uris == null || uris.isEmpty() || job != null) return;
        final ApiClient.Task task = begin("导入自建库");
        worker = new Thread(() -> {
            int added = 0; String firstError = "";
            // 题录导出（.bib/.ris/NoteExpress/知网纯文本）不能整份当一篇文章塞进库：
            // 那样五百条题录并成一条，来源榜只会指着"这份导出文件"报字数。
            ArrayList<String> recordNotes = new ArrayList<>();
            for (int i = 0; i < uris.size(); i++) {
                if (task.cancelled()) break;
                progress("导入 " + (i + 1) + "/" + uris.size());
                try {
                    byte[] bytes = readAll(uris.get(i));
                    String name = MainActivity.displayName(activity, uris.get(i));
                    RecordImport.Parsed parsed = RecordImport.parse(bytes, name);
                    if (RecordImport.prefersRecords(bytes, name, parsed)) {
                        int imported = recordBatch(parsed, task);
                        recordNotes.add(parsed.summary() + "，入库 " + imported + " 篇");
                        added += imported;
                        continue;
                    }
                    String error = library.addDocument(name, bytes);
                    if (error == null) added++; else if (firstError.isEmpty()) firstError = error;
                } catch (Exception error) { if (firstError.isEmpty()) firstError = "读取失败"; }
            }
            final int count = added;
            final String tail = !recordNotes.isEmpty() ? "，其中题录按篇分开入库（" + recordNotes.get(0) + "）"
                    : firstError.isEmpty() ? "" : "（" + firstError + "）";
            complete(task, () -> toast("已导入 " + count + " 篇" + tail));
        }, "wordlite-library"); worker.start();
    }

    /** 一份题录导出一整批进自建库：一篇一条，去重与容量都走 CorpusImport 那三条规矩。 */
    private int recordBatch(RecordImport.Parsed parsed, ApiClient.Task task) {
        CorpusImport.Batch batch = RecordImport.into(library, parsed,
                (done, total, receipt) -> {
                    if (done % 20 == 0 || done == total) progress("题录入库 " + done + "/" + total);
                },
                task::cancelled);
        if (batch.failed() > 0) noteLibrary(batch.firstProblem());
        return batch.imported();
    }

    /** 自建库导入路径上的注记：这一层没有 Report 可写，落到 toast 上。 */
    private void noteLibrary(String message) {
        if (message == null || message.isEmpty()) return;
        activity.runOnUiThread(() -> toast(message));
    }
    private byte[] readAll(Uri uri) throws java.io.IOException {
        InputStream input = activity.getContentResolver().openInputStream(uri);
        if (input == null) throw new java.io.IOException();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) {
                if (out.size() > MAX_LIBRARY_FILE) throw new java.io.IOException("文件过大");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } finally { input.close(); }
    }
    /** 自检用的短语：中文库和英文库各给一个查得出东西的说法，免得把"语种不对"误报成"连不上"。 */
    private static final String PROBE_CHINESE = "图像分割";
    private static final String PROBE_ENGLISH = "image segmentation";

    /**
     * 检索自检。查重报告里"没有重复"和"一个库都没连上"长得太像，而手机的网络、代理、
     * 库自己的脸色，坏哪一样都只是"没查到"。这里对每个启用中的源真发一次检索，
     * 把命中数、耗时和这一趟实际走的路（直连还是经哪个代理）如实列出来。
     */
    private void engineProbe() {
        final ArrayList<String> wanted = new ArrayList<String>(engine.engines);
        if (job != null) return;
        if (wanted.isEmpty()) { toast("没有勾选检索源"); return; }
        final ApiClient.Task task = begin("检索自检");
        final EngineSettings options = engine.copy();
        Routes.reset();
        worker = new Thread(() -> {
            PaperSources.Limits limits = new PaperSources.Limits();
            limits.timeoutSeconds = options.timeoutSeconds;
            limits.perEngine = 3;
            limits.coreKey = options.coreKey;
            limits.proxy = options.proxy;
                limits.solver = options.solver;
            final StringBuilder lines = new StringBuilder();
            int usable = 0;
            for (int i = 0; i < wanted.size(); i++) {
                if (task.cancelled()) break;
                String id = wanted.get(i);
                String host = Routes.host(PaperSources.endpoint(id));
                progress(PaperSources.label(id) + " " + (i + 1) + "/" + wanted.size());
                long started = System.nanoTime();
                String outcome;
                try {
                    usable++;
                    ArrayList<PaperSources.Candidate> found =
                            PaperSources.search(id, chinese(id) ? PROBE_CHINESE : PROBE_ENGLISH,
                                    limits, task);
                    outcome = found.isEmpty() ? "0 条（连通，但没查到）" : found.size() + " 条";
                } catch (Exception error) { outcome = concise(error); }
                appendProbe(lines, PaperSources.label(id), outcome,
                        (System.nanoTime() - started) / 1000000, Routes.routeFor(host));
            }
            final String text = lines.toString().trim() + hint(usable, options.proxy);
            final String tail = usable + "/" + wanted.size() + " 个源可用";
            complete(task, () -> probeResult(text, tail));
        }, "wordlite-probe"); worker.start();
    }

    /** 中文库判定只看域名，和真正选路时用的是同一条规则，两边不会说出不一致的话。 */
    private static boolean chinese(String engineId) {
        return Routes.domestic(Routes.host(PaperSources.endpoint(engineId)));
    }

    private static void appendProbe(StringBuilder out, String name, String outcome, long millis, String route) {
        if (out.length() > 0) out.append("\n");
        out.append(name).append("\n    ").append(outcome).append("  ·  ")
                .append(millis).append("ms  ·  ").append(route).append("\n");
    }

    /**
     * 一个源都没连上时，光说"网络失败"等于什么都没说：这台手机在自己的网络上对十个检索主机
     * 全部 ECONNREFUSED，而同一份代码经电脑上的代理就全通。所以把找过的路原样列出来，
     * 再说一句该往哪儿填地址，用户才有下一步可做。
     */
    private static String hint(int usable, String proxy) {
        if (usable > 0) return "";

        String out = "\n\n找过的路：" + Routes.candidateList(proxy)
                + "\n手机自己出不去时，让流量经电脑上的代理出去：检索设置里的\"HTTP 代理\"填电脑的地址与端口"
                + "（例如 192.168.1.20:7897，代理端需允许局域网连接）。";
        if (Routes.anyPortDown())
            /* 回环端口被拒过一次，说明手机上这会儿没有代理在听，而直连也没出去。这时候最省事的下一步
               是那条脚本：它自己探端口、自己建反代、再用应用自己的检索代码确认一遍，用户不必去猜
               电脑上那个端口到底是 7890 还是 7897（实测多数人以为是 7890，实际在听的常常是 7897）。 */
            return out + "\n回环上的代理端口没人监听：用数据线连上电脑，在电脑上执行 pwsh tools/phone-gateway.ps1，"
                    + "它探出真正在监听的端口、反代进手机、再拿应用自己的代码验一次。反代之后这一格留空也能自动找到。";
        /* 反代进手机的端口是 7897 还是 7890 都行：这两个回环端口 Routes 都会自动发现，
           所以这一格留空也用得上代理，不必让用户去猜电脑上那个端口是几号。 */
        return out + "\nUSB 连着电脑时也可以自己转一条：adb reverse tcp:7897 tcp:<电脑上的代理端口>"
                + "（Clash Verge 默认监听 7897，未必是 7890）。反代进来之后这一格留空也能自动找到。";
    }
    private void probeResult(String text, String tail) {
        LinearLayout box = column(); box.setPadding(dp(16), dp(8), dp(16), dp(8));
        box.addView(label(text.isEmpty() ? "没有完成任何一次自检" : text, 13));
        ScrollView scroll = new ScrollView(activity); scroll.addView(box);
        new AlertDialog.Builder(activity).setTitle("检索自检（" + tail + "）").setView(scroll)
                .setNegativeButton("关闭", null).show();
    }

    /**
     * 知网没有匿名检索口的那部分，用这个补：手上的文献号或文章页链接直接变成自建库里的一篇，
     * 刊期号则把当期目录导进来（一期几十篇，一次最多 CnkiTouch.MAX_IMPORT 篇）。
     * 落库之后就和导入的文档一样参与本机比对。
     */
    private void cnkiImport() {
        final EditText input = field("", "粘贴知网文献号或文章页/刊期页链接");
        LinearLayout box = column(); box.setPadding(dp(16), dp(8), dp(16), dp(8)); box.addView(input);
        ScrollView scroll = new ScrollView(activity); scroll.addView(box);
        new AlertDialog.Builder(activity).setTitle("从知网导入自建库").setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", (dialog, which) -> startCnkiImport(input.getText().toString()))
                .show();
    }

    private void startCnkiImport(final String raw) {
        final String code = CnkiTouch.code(raw);
        if (job != null) return;
        if (code.isEmpty()) { toast("认不出这个知网文献号"); return; }
        final ApiClient.Task task = begin("从知网导入");
        worker = new Thread(() -> {
            int added = 0;
            String error = "";
            try {
                java.net.Proxy via = Routes.parse(engine.proxy);
                ArrayList<String> codes = new ArrayList<String>();
                if (CnkiTouch.isIssue(code)) {
                    codes.addAll(CnkiTouch.issueCodes(CnkiTouch.get(CnkiTouch.issueUrl(code),
                            engine.timeoutSeconds, via, task), CnkiTouch.MAX_IMPORT));
                    if (codes.isEmpty()) error = "当期目录里没找到文献号";
                } else codes.add(code);
                for (int i = 0; i < codes.size(); i++) {
                    if (task.cancelled()) break;
                    progress("导入 " + (i + 1) + "/" + codes.size());
                    String bad = importCnki(codes.get(i), via, task);
                    if (bad == null) added++;
                    else if (error.isEmpty()) error = bad;
                }
            } catch (Exception failure) { error = concise(failure); }
            final int count = added;
            final String tail = error.isEmpty() ? "" : "（" + error + "）";
            complete(task, () -> toast("已入库 " + count + " 篇" + tail));
        }, "wordlite-cnki"); worker.start();
    }

    /** 成功返回 null。期刊页取不到就换会议论文的路由再试一次：同一个编号在两个库里不会同时存在。 */
    private String importCnki(String code, java.net.Proxy via, ApiClient.Cancellation task) {
        String[] urls = { CnkiTouch.articleUrl(code), CnkiTouch.conferenceUrl(code) };
        for (String url : urls) {
            try {
                String text = CnkiTouch.record(CnkiTouch.get(url, engine.timeoutSeconds, via, task));
                if (text.trim().length() < 24) continue;
                String bad = library.addDocument("知网-" + code + ".txt",
                        text.getBytes(StandardCharsets.UTF_8));
                return bad;
            } catch (Exception error) {
                if (task.cancelled()) return "已取消";
            }
        }
        return "没取到 " + code + " 的题录";
    }
    private void engineSettings() {
        LinearLayout box = column(); box.setPadding(dp(16), dp(8), dp(16), dp(8));
        final CheckBox web = check("启用联网检索", engine.web); box.addView(web);
        final LinkedHashMap<String, CheckBox> boxes = new LinkedHashMap<String, CheckBox>();
        for (String id : PaperSources.engines()) {
            CheckBox item = check(engineTitle(id), engine.engines.contains(id));
            boxes.put(id, item); box.addView(item);
        }
        final EditText perEngine = field(String.valueOf(engine.perEngine), "每个引擎候选数（1-50）");
        final EditText timeout = field(String.valueOf(engine.timeoutSeconds), "超时秒数（5-120）");
        /* 这个数的口径写在自己脸上：它管的是"每家最多问几扇"，不是"整轮只看前几扇"——
           后者是 2.6.2 之前的实际行为，那会儿 12 意味着第 9 扇之后一个字都没查。 */
        final EditText windows = field(String.valueOf(engine.windows),
                "每源检索窗口数（1-24，每家最多问几扇）");
        final CheckBox autoPdf = check("扫描时顺手抓开放获取全文：存进自建库并按正文比对"
                + "（用剩下的检索请求额度）", engine.autoPdf);
        box.addView(autoPdf);
        final EditText autoPdfs = field(String.valueOf(engine.autoPdfs), "顺手抓正文最多几篇（0-10，0 为关掉）");
        box.addView(autoPdfs);
        final EditText core = field(engine.coreKey, "CORE API Key（可留空）");
        final EditText proxy = field(engine.proxy, "HTTP 代理 host:port（海外检索源需经电脑代理时填写，可留空）");
        final EditText solver = field(engine.solver,
                "过验证服务地址（FlareSolverr，例如 http://192.168.1.20:8191；留空则自动试电脑上的 127.0.0.1:8191）");
        box.addView(perEngine); box.addView(timeout); box.addView(windows); box.addView(core);
        box.addView(proxy); box.addView(solver);
        ScrollView scroll = new ScrollView(activity); scroll.addView(box);
        new AlertDialog.Builder(activity).setTitle("检索设置").setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    EngineSettings value = new EngineSettings();
                    value.web = web.isChecked();
                    value.engines.clear();
                    for (Map.Entry<String, CheckBox> entry : boxes.entrySet())
                        if (entry.getValue().isChecked()) value.engines.add(entry.getKey());
                    value.perEngine = number(perEngine.getText().toString(), engine.perEngine);
                    value.timeoutSeconds = number(timeout.getText().toString(), engine.timeoutSeconds);
                    value.windows = number(windows.getText().toString(), engine.windows);
                    value.autoPdf = autoPdf.isChecked();
                    value.autoPdfs = number(autoPdfs.getText().toString(), engine.autoPdfs);
                    value.coreKey = core.getText().toString().trim();
                    value.proxy = proxy.getText().toString().trim();
                    value.solver = solver.getText().toString().trim();
                    try { value.validate(); settings.saveEngine(value); engine = value; toast("已保存检索设置"); }
                    catch (Exception error) { toast(error instanceof IllegalArgumentException
                            ? error.getMessage() : "设置保存失败"); }
                }).show();
    }
    private CheckBox check(String text, boolean value) {
        CheckBox box = new CheckBox(activity);
        box.setText(text); box.setTextSize(14); box.setTextColor(ink); box.setChecked(value);
        return box;
    }
    private EditText field(String value, String hint) {
        EditText field = new EditText(activity);
        field.setText(value); field.setHint(hint); field.setTextSize(14); field.setTextColor(ink);
        field.setSingleLine();
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        return field;
    }
    private static int number(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (Exception error) { return fallback; }
    }
    private static String engineTitle(String id) {
        if ("cnki".equals(id)) return "知网（期刊与会议论文，题录与摘要预览）";
        if ("wanfang".equals(id)) return "万方数据（期刊与学位论文摘要）";
        if ("cqvip".equals(id)) return "维普（中文期刊摘要，服务端只给第一页）";
        if ("ncpssd".equals(id)) return "国家哲学社会科学文献中心（含摘要）";
        if ("openalex".equals(id)) return "OpenAlex（题录与开放获取全文）";
        if ("crossref".equals(id)) return "Crossref（题录与摘要）";
        if ("semantic-scholar".equals(id)) return "Semantic Scholar";
        if ("europepmc".equals(id)) return "Europe PMC（生物医学全文）";
        if ("arxiv".equals(id)) return "arXiv 预印本";
        if ("core".equals(id)) return "CORE（需 API Key）";
        return id;
    }
    /** Offline paraphrase: same protected islands, no endpoint involved. */
    private void rewriteLocal(int scope) {
        host.sync();
        final ArrayList<String> terms = new ArrayList<String>();
        try { terms.addAll(settings.load(ApiConfig.Service.REWRITE).terms); } catch (Exception ignored) { }
        ArrayList<RewriteTask> targets = new ArrayList<RewriteTask>();
        try {
            if (scope == 2) {
                boolean bibliography = false;
                for (DocxDocument.ParagraphBlock paragraph : host.document().paragraphs) {
                    if (paragraph.isHeading && paragraph.text.trim().matches("^(?:[0-9.\\s]*)(参考文献|References|Bibliography)\\s*$")) bibliography = true;
                    if (bibliography || paragraph.isHeading || paragraph.text.trim().isEmpty()
                            || TextProtection.referenceParagraph(paragraph)) continue;
                    try { targets.add(new RewriteTask(paragraph, 0, paragraph.text.length(), terms)); }
                    catch (IllegalArgumentException ignored) { }
                }
            } else {
                DocxDocument.ParagraphBlock paragraph = host.currentParagraph();
                if (paragraph == null) throw new IllegalArgumentException("没有选中内容");
                int[] range = scope == 0 ? host.selectedRange() : new int[]{0, paragraph.text.length()};
                TextSelection.paragraph(paragraph, range[0], range[1]);
                targets.add(new RewriteTask(paragraph, range[0], range[1], terms));
            }
            if (targets.isEmpty()) throw new IllegalArgumentException("没有可改写内容");
        } catch (IllegalArgumentException error) { toast(error.getMessage()); return; }
        rewrites.clear(); rewrites.addAll(targets); rewriteConfig = null; rewriteCursor = 0;
        preRewriteText = snapshotText();
        final ApiClient.Task task = begin("离线改写");
        final ArrayList<RewriteTask> jobs = new ArrayList<RewriteTask>(targets);
        worker = new Thread(() -> {
            for (int i = 0; i < jobs.size(); i++) {
                if (task.cancelled()) break;
                RewriteTask item = jobs.get(i);
                progress("改写第 " + (i + 1) + "/" + jobs.size() + " 段");
                ArrayList<String> values = new ArrayList<String>();
                for (LocalRewriter.Option option : LocalRewriter.rewrite(item.mask.submitted, terms))
                    if (!values.contains(option.text)) values.add(option.text);
                if (values.isEmpty()) { item.suggestions.clear(); item.error = "没有适用的离线规则"; }
                else try { item.suggestions(values); item.error = ""; }
                catch (IllegalArgumentException error) { item.suggestions.clear(); item.error = "改写结果未保留引用"; }
            }
            complete(task, this::compare);
        }, "wordlite-rewrite-local"); worker.start();
    }
    /** Worker-thread progress line; dropped once the dialog is gone. */
    /**
     * 查重结果页上的"降重并复核"。判据、采纳口径、回退规则全在 RewriteLoop 里（那一份与查重
     * 用的是同一个 TextCorpus.match），这一层只管线程、进度、取消和回写：手机上一轮整篇验证
     * 是唯一贵的动作，所以预算按命中处数给，取消按钮随时能停。
     */
    private void rewriteVerified() {
        host.sync();
        final TextSelection selection = lastScanned;
        final DuplicateEngine.Report scan = lastScan;
        if (selection == null || scan == null || scan.hits.isEmpty()) {
            toast("先做一次查重，才知道哪几段判为重复"); return;
        }
        if (!selection.unchanged(host.document())) { toast("文档已经改过，重新查重之后再降重"); return; }
        final ArrayList<String> terms = new ArrayList<String>();
        try { terms.addAll(settings.load(ApiConfig.Service.REWRITE).terms); } catch (Exception ignored) { }
        final RewriteLoop.Limits limits = new RewriteLoop.Limits();
        limits.regions = Math.max(limits.regions, scan.hits.size());
        limits.verifications = 12 * Math.max(1, scan.hits.size());
        preRewriteText = snapshotText();
        final ApiClient.Task task = begin("降重并复核");
        worker = new Thread(() -> {
            final RewriteLoop.Result out;
            final TextCorpus corpus = new TextCorpus();
            try {
                library.index(corpus);
                out = RewriteLoop.run(selection.text, corpus, terms, limits, new RewriteLoop.Listener() {
                    public void onProgress(String stage, int done, int total) {
                        progress(total > 0 ? stage + " " + done + "/" + total : stage);
                    }
                    public boolean cancelled() { return task.cancelled(); }
                });
            } catch (Exception error) { fail(task, error); return; }
            /* 取消不是回滚：已经采纳的每一条都让整篇命中字数变小过，所以取消那一轮照样要落盘。
               complete() 在取消时不跑回调，这里自己回主线程。 */
            activity.runOnUiThread(() -> {
                if (job != task) return;
                job = null; worker = null; host.busy(false); jobLabel = null;
                if (progressDialog != null) { progressDialog.dismiss(); progressDialog = null; }
                if (alive()) reviewRewrite(selection, out, corpus);
            });
        }, "wordlite-rewrite-loop"); worker.start();
    }

    /** 落笔：从最后一条往前替换，段落号是 RewriteLoop.edits 自己校验过的。返回真写进去的条数。 */
    private int applyEdits(ArrayList<RewriteLoop.Edit> edits) {
        int applied = 0;
        try {
            for (int i = edits.size() - 1; i >= 0; i--) {
                RewriteLoop.Edit edit = edits.get(i);
                DocxDocument.ParagraphBlock paragraph = TextSelection.find(host.document(), edit.paragraphIndex);
                host.beforeRewrite(edit.paragraphIndex);
                TextRewriter.replace(host.document(), paragraph, edit.start, edit.end, edit.replacement);
                applied++;
            }
        } catch (IllegalArgumentException error) { toast(error.getMessage()); return applied; }
        if (applied > 0) host.changed();
        return applied;
    }

    /**
     * 闭环跑完先让人逐段过一遍再落笔。一段一行，写清这段单独对着语料改前改后各命中多少字，
     * 勾了才写进文档：默认全勾（和以前一样），但任何人都能把某一段退回原文。
     * 顺序与 RewriteLoop.edits() 相同，所以勾掉一行就等于少落对应那一条替换。
     */
    private void reviewRewrite(final TextSelection selection, final RewriteLoop.Result result,
                               final TextCorpus corpus) {
        final ArrayList<RewriteLoop.Edit> all;
        try { all = RewriteLoop.edits(selection, result, selection.text); }
        catch (IllegalArgumentException error) { toast(error.getMessage()); return; }
        if (all.isEmpty()) { showRewriteLoopResult(selection, result, 0); return; }
        final ArrayList<RewriteLoop.Review> rows = RewriteLoop.review(selection, result, selection.text);
        final java.util.LinkedHashSet<Integer> kept = new java.util.LinkedHashSet<Integer>();
        for (int i = 0; i < all.size(); i++) kept.add(Integer.valueOf(all.get(i).paragraphIndex));
        LinearLayout box = column(); box.setPadding(dp(16), dp(8), dp(16), dp(8));
        box.addView(label(result.verdict, 15));
        box.addView(label(result.measured
                ? String.format(Locale.CHINA, "闭环测得选区 %.2f%% → %.2f%%。下面 %d 段是它改过的，勾掉的段落保持原文不动。",
                        Double.valueOf(result.rateBefore), Double.valueOf(result.rateAfter),
                        Integer.valueOf(all.size()))
                : result.reason, 13));
        int shown = 0;
        for (int i = 0; i < rows.size() && shown < 20; i++) {
            final RewriteLoop.Review row = rows.get(i);
            CheckBox mark = new CheckBox(activity);
            mark.setChecked(true);
            mark.setPadding(dp(4), dp(6), dp(4), dp(6));
            mark.setText(String.format(Locale.CHINA, "第 %d 段 · 命中 %d → %d 字%s\n%s",
                    Integer.valueOf(row.paragraphIndex), Integer.valueOf(row.dupBefore),
                    Integer.valueOf(row.dupAfter), row.cleared ? "（已清）" : "",
                    safeSlice(row.adopted, 0, 64)));
            mark.setOnCheckedChangeListener((view, on) -> {
                if (on) kept.add(Integer.valueOf(row.paragraphIndex));
                else kept.remove(Integer.valueOf(row.paragraphIndex));
            });
            box.addView(mark);
            shown++;
        }
        if (all.size() > shown) box.addView(label("其余 " + (all.size() - shown) + " 段没有列出来，默认一并采纳。", 12));
        ScrollView scroll = new ScrollView(activity); scroll.addView(box);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("降重准备改的段落")
                .setView(scroll).setPositiveButton("采纳", null)
                .setNegativeButton("一段都不要", null).create();
        dialog.show();
        Button accept = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (accept != null) accept.setText(String.format(Locale.CHINA, "采纳 %d 处", Integer.valueOf(all.size())));
        if (accept != null) accept.setOnClickListener(view -> {
            dialog.dismiss();
            ArrayList<RewriteLoop.Edit> chosen = new ArrayList<RewriteLoop.Edit>();
            for (int i = 0; i < all.size(); i++)
                if (kept.contains(Integer.valueOf(all.get(i).paragraphIndex))) chosen.add(all.get(i));
            if (chosen.isEmpty()) { toast("一段都没勾，文档没动"); return; }
            final int applied = applyEdits(chosen);
            if (applied == 0) return;
            if (corpus == null || corpus.isEmpty() || preRewriteText.isEmpty()
                    || applied < chosen.size()) { showRewriteLoopResult(selection, result, applied); return; }
            measureRewriteEffect(result, applied, all.size(), corpus);
        });
    }

    /** 采纳完之后拿同一份基线把全文前后各检一次，前后两个数由 DuplicateEngine.compareRewrite 一个人写。 */
    private void measureRewriteEffect(final RewriteLoop.Result result, final int applied,
                                      final int offered, final TextCorpus corpus) {
        final String before = preRewriteText, after = snapshotText();
        if (after.isEmpty()) { showRewriteLoopResult(lastScanned, result, applied); return; }
        final ApiClient.Task task = begin("复核降重效果");
        worker = new Thread(() -> {
            final DuplicateEngine.RewriteDelta delta;
            try { delta = DuplicateEngine.compareRewrite(before, after, corpus); }
            catch (Exception error) { fail(task, error); return; }
            complete(task, () -> showRewriteEffect(delta, applied, offered, result.budgetHit));
        }, "wordlite-rewrite-measure"); worker.start();
    }

    private void showRewriteEffect(DuplicateEngine.RewriteDelta delta, int applied, int offered,
                                   boolean budgetHit) {
        LinearLayout box = column(); box.setPadding(dp(16), dp(8), dp(16), dp(8));
        box.addView(label(delta.verdict, 15));
        box.addView(label(String.format(Locale.CHINA, "全文 %.2f%% → %.2f%%  ·  重复 %d/%d 字",
                Double.valueOf(delta.beforeRate), Double.valueOf(delta.afterRate),
                Integer.valueOf(delta.afterDuplicate), Integer.valueOf(delta.afterCompared)), 13));
        box.addView(label(String.format(Locale.CHINA, "采纳 %d/%d 处改动，没勾的段落保持原文。",
                Integer.valueOf(applied), Integer.valueOf(offered)), 12));
        ScrollView scroll = new ScrollView(activity); scroll.addView(box);
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle("降重结果")
                .setView(scroll).setNegativeButton("关闭", null);
        if (budgetHit) dialog.setPositiveButton("再跑一轮", (d, which) -> rewriteVerified());
        dialog.show();
    }

    private void showRewriteLoopResult(TextSelection selection, RewriteLoop.Result result, int applied) {
        LinearLayout box = column(); box.setPadding(dp(16), dp(8), dp(16), dp(8));
        box.addView(label(result.verdict, 15));
        if (result.measured)
            box.addView(label(String.format(Locale.CHINA, "重复率 %.2f%% → %.2f%%  ·  重复 %d/%d 字  ·  改写 %d 处",
                    result.rateBefore, result.rateAfter, result.dupAfter, result.comparedChars, applied), 13));
        else box.addView(label(result.reason, 13));
        box.addView(label(String.format(Locale.CHINA, "命中区 %d 处压下去 %d 处  ·  整篇验证 %d 次  ·  退回候选 %d 个",
                result.regions, result.regionsImproved, result.verified, result.rejected), 12));
        int shown = 0;
        for (RewriteLoop.Segment segment : RewriteLoop.details(result)) {
            if (segment.cleared || segment.dupAfter <= 0) continue;
            if (shown++ == 0) box.addView(label("改了但单独比对仍判为重复的段落：", 13));
            if (shown > 8) { box.addView(label("其余从略。", 11)); break; }
            box.addView(label(String.format(Locale.CHINA, "仍命中 %d 字（最高 %.3f）：%s",
                    segment.dupAfter, segment.scoreAfter,
                    safeSlice(selection.text, segment.start, segment.end)), 12));
        }
        ScrollView scroll = new ScrollView(activity); scroll.addView(box);
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle("降重结果")
                .setView(scroll).setNegativeButton("关闭", null);
        if (result.budgetHit) dialog.setPositiveButton("再跑一轮", (d, which) -> rewriteVerified());
        dialog.show();
    }

    private void progress(final String value) {
        activity.runOnUiThread(() -> { if (jobLabel != null && alive()) jobLabel.setText(value); });
    }
    public void rewriteMenu() {
        if (job != null || host.document() == null) return;
        final ArrayList<String> ids = new ArrayList<String>();
        final ArrayList<String> titles = new ArrayList<String>();
        titles.add("选中文本"); ids.add("api-0");
        titles.add("当前段落"); ids.add("api-1");
        titles.add("全文"); ids.add("api-2");
        titles.add("离线改写选区"); ids.add("local-0");
        titles.add("离线改写当前段落"); ids.add("local-1");
        titles.add("离线改写全文"); ids.add("local-2");
        if (!rewrites.isEmpty()) { titles.add("改写建议"); ids.add("compare"); }
        if (!preRewriteText.isEmpty() || lastScan != null) { titles.add("改写效果"); ids.add("delta"); }
        new AlertDialog.Builder(activity).setTitle("降重")
                .setItems(titles.toArray(new String[titles.size()]), (dialog, which) -> {
            String id = ids.get(which);
            if ("compare".equals(id)) compare();
            else if ("delta".equals(id)) rewriteDelta();
            else if (id.startsWith("local-")) rewriteLocal(Integer.parseInt(id.substring(6)));
            else rewrite(Integer.parseInt(id.substring(4)));
        }).setNegativeButton("取消", null).show();
    }
/** 改写前的全文快照，用来和改写后各检一次。 */
    private String snapshotText() {
        try { return TextSelection.all(host.document()).text; } catch (Exception ignored) { return ""; }
    }

    /** 改写前后各检一次，同一份基线，效果由数字说话。 */
    private void rewriteDelta() {
        host.sync();
        String after;
        try {
            after = TextSelection.all(host.document()).text;
        } catch (IllegalArgumentException error) { toast(error.getMessage()); return; }
        String before = !preRewriteText.isEmpty() ? preRewriteText
                : lastScanned == null ? "" : lastScanned.text;
        if (before.isEmpty()) { toast("先改写一段或先做一次查重，再来看效果"); return; }
        TextCorpus corpus = lastScan == null ? null : lastScan.baseline;
        if (corpus == null || corpus.isEmpty()) {
            corpus = new TextCorpus();
            try { library.index(corpus); } catch (Exception ignored) { }
        }
        if (corpus.isEmpty()) { toast("比对基线是空的：先导入自建库或做一次联网查重"); return; }
        DuplicateEngine.RewriteDelta delta = DuplicateEngine.compareRewrite(before, after, corpus);
        new AlertDialog.Builder(activity).setTitle("改写效果").setMessage(delta.verdict)
                .setNeutralButton("关闭", null).show();
    }

    private void rewrite(int scope) {
        final ApiConfig config = config(ApiConfig.Service.REWRITE); if (config == null) return;
        host.sync(); ArrayList<RewriteTask> targets = new ArrayList<RewriteTask>();
        try {
            if (scope == 2) {
                boolean bibliography = false;
                for (DocxDocument.ParagraphBlock paragraph : host.document().paragraphs) {
                    if (paragraph.isHeading && paragraph.text.trim().matches("^(?:[0-9.\\s]*)(参考文献|References|Bibliography)\\s*$")) bibliography = true;
                    if (bibliography || paragraph.isHeading || paragraph.text.trim().isEmpty() || TextProtection.referenceParagraph(paragraph)) continue;
                    try { targets.add(new RewriteTask(paragraph, 0, paragraph.text.length(), config)); }
                    catch (IllegalArgumentException error) { continue; }
                }
            } else {
                DocxDocument.ParagraphBlock paragraph = host.currentParagraph();
                if (paragraph == null) throw new IllegalArgumentException("没有选中内容");
                int[] range = scope == 0 ? host.selectedRange() : new int[]{0, paragraph.text.length()};
                TextSelection.paragraph(paragraph, range[0], range[1]);
                targets.add(new RewriteTask(paragraph, range[0], range[1], config));
            }
            if (targets.isEmpty()) throw new IllegalArgumentException("没有可改写内容");
        } catch (IllegalArgumentException error) { toast(error.getMessage()); return; }
        rewrites.clear(); rewrites.addAll(targets); rewriteConfig = config; rewriteCursor = 0;
        preRewriteText = snapshotText();
        requestRewrites(new ArrayList<RewriteTask>(targets));
    }
    private void requestRewrites(ArrayList<RewriteTask> targets) {
        final ApiClient.Task task = begin("降重"); final ApiConfig config = rewriteConfig;
        worker = new Thread(() -> {
            for (int i = 0; i < targets.size(); i++) {
                if (task.cancelled()) break;
                RewriteTask item = targets.get(i);
                try {
                    ApiClient.Response response = ApiClient.execute(config, item.mask.submitted, null, host.fileName(), task);
                    item.suggestions(ApiResult.suggestions(config, response)); item.error = ""; item.rejected = false;
                } catch (Exception error) { item.suggestions.clear(); item.error = concise(error); }
            }
            complete(task, this::compare);
        }, "word-api-rewrite"); worker.start();
    }
    private void compare() {
        if (rewrites.isEmpty() || !alive()) return;
        rewriteCursor = Math.max(0, Math.min(rewriteCursor, rewrites.size() - 1));
        final RewriteTask task = rewrites.get(rewriteCursor);
        LinearLayout box = column(); box.setPadding(dp(14), dp(8), dp(14), dp(8)); box.setBackgroundColor(surface);
        TextView count = label((rewriteCursor + 1) + " / " + rewrites.size()
                + (task.accepted ? " · 已接受" : task.rejected ? " · 已拒绝" : ""), 12); box.addView(count);
        LinearLayout split = new LinearLayout(activity);
        boolean wide = activity.getResources().getDisplayMetrics().widthPixels / activity.getResources().getDisplayMetrics().density >= 600;
        split.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        String rewritten = task.suggestions.isEmpty() ? task.error : task.rewritten();
        TextDiff diff = new TextDiff(task.mask.original, rewritten);
        LinearLayout original = comparison("原文", task.mask.original, diff.start, diff.originalEnd, 0x44D85858);
        LinearLayout changed = comparison("改写", rewritten, diff.start, diff.changedEnd, 0x442F9C60);
        split.addView(original, wide ? new LinearLayout.LayoutParams(0, dp(260), 1) : new LinearLayout.LayoutParams(-1, dp(150)));
        split.addView(changed, wide ? new LinearLayout.LayoutParams(0, dp(260), 1) : new LinearLayout.LayoutParams(-1, dp(150)));
        box.addView(split);
        final AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("改写对比").setView(box).setNegativeButton("关闭", null).create();
        LinearLayout actions = new LinearLayout(activity);
        Button previous = action("‹", () -> { dialog.dismiss(); rewriteCursor = (rewriteCursor - 1 + rewrites.size()) % rewrites.size(); compare(); });
        Button accept = action("接受", () -> {
            try {
                host.sync(); if (!task.unchanged(host.document())) throw new IllegalArgumentException("原文已更改");
                host.beforeRewrite(task.paragraphIndex);
                TextRewriter.apply(host.document(), TextSelection.find(host.document(), task.paragraphIndex), task.start, task.mask, task.raw());
                task.accepted = true; task.rejected = false; host.changed(); dialog.dismiss(); nextComparison();
            } catch (IllegalArgumentException error) { toast(error.getMessage()); }
        });
        Button reject = action("拒绝", () -> { task.rejected = true; dialog.dismiss(); nextComparison(); });
        Button regenerate = action("重新生成", () -> {
            if (!task.unchanged(host.document())) { toast("原文已更改"); return; }
            dialog.dismiss(); ArrayList<RewriteTask> targets = new ArrayList<RewriteTask>(); targets.add(task); requestRewrites(targets);
        });
        Button next = action("›", () -> { dialog.dismiss(); rewriteCursor = (rewriteCursor + 1) % rewrites.size(); compare(); });
        accept.setEnabled(!task.accepted && !task.rejected && !task.suggestions.isEmpty());
        reject.setEnabled(!task.accepted && !task.rejected); regenerate.setEnabled(!task.accepted);
        actions.addView(previous); actions.addView(accept); actions.addView(reject); actions.addView(regenerate); actions.addView(next);
        android.widget.HorizontalScrollView buttons = new android.widget.HorizontalScrollView(activity); buttons.setHorizontalScrollBarEnabled(false); buttons.addView(actions); box.addView(buttons);
        if (task.suggestions.size() > 1) {
            Spinner alternatives = new Spinner(activity); String[] choices = new String[task.suggestions.size()];
            for (int i = 0; i < choices.length; i++) choices[i] = "建议 " + (i + 1);
            alternatives.setAdapter(new android.widget.ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, choices));
            alternatives.setSelection(task.selectedSuggestion); box.addView(alternatives);
            alternatives.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    if (position != task.selectedSuggestion) { task.selectedSuggestion = position; dialog.dismiss(); compare(); }
                }
                public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            });
        }
        dialog.show();
    }
    private void nextComparison() {
        for (int i = 1; i <= rewrites.size(); i++) {
            int next = (rewriteCursor + i) % rewrites.size(); RewriteTask item = rewrites.get(next);
            if (!item.accepted && !item.rejected) { rewriteCursor = next; compare(); return; }
        }
    }
    private LinearLayout comparison(String title, String value, int start, int end, int color) {
        LinearLayout box = column(); box.addView(label(title, 12)); ScrollView scroll = new ScrollView(activity);
        TextView text = label("", 14); text.setTextIsSelectable(true);
        SpannableString marked = new SpannableString(value);
        if (end > start && start >= 0 && end <= value.length()) marked.setSpan(new BackgroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setText(marked); text.setPadding(dp(8), dp(8), dp(8), dp(8)); scroll.addView(text);
        box.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); return box;
    }
    private ApiClient.Task begin(String title) {
        host.busy(true); final ApiClient.Task task = new ApiClient.Task(); job = task;
        LinearLayout box = column(); box.setPadding(dp(22), dp(16), dp(22), dp(4));
        jobLabel = label(title, 13); box.addView(jobLabel);
        ProgressBar progress = new ProgressBar(activity);
        box.addView(progress);
        progressDialog = new AlertDialog.Builder(activity).setTitle(title).setView(box)
                .setNegativeButton("取消", (dialog, which) -> task.cancel()).create();
        progressDialog.setOnCancelListener(dialog -> task.cancel()); progressDialog.show(); return task;
    }
    private void complete(ApiClient.Task task, Runnable callback) {
        activity.runOnUiThread(() -> {
            if (job != task) return; job = null; worker = null; host.busy(false); jobLabel = null;
            if (!alive()) return;
            if (progressDialog != null) { progressDialog.dismiss(); progressDialog = null; }
            if (!task.cancelled()) callback.run();
        });
    }
    private void fail(ApiClient.Task task, Exception error) { complete(task, () -> toast(concise(error))); }
    private static String concise(Exception error) {
        return error instanceof ApiClient.Failure || error instanceof IllegalArgumentException ? error.getMessage() : "请求失败";
    }
    public void destroy() {
        destroyed = true; if (job != null) job.cancel(); if (worker != null) worker.interrupt();
        if (progressDialog != null) { progressDialog.dismiss(); progressDialog = null; }
    }
    private boolean alive() { return !destroyed && !activity.isFinishing() && !activity.isDestroyed(); }
    private Button action(String text, Runnable callback) {
        Button button = new Button(activity); button.setText(text); button.setTextSize(12); button.setAllCaps(false); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setOnClickListener(view -> callback.run()); return button;
    }
    private LinearLayout column() { LinearLayout box = new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL); return box; }
    private TextView label(String value, int size) { TextView text = new TextView(activity); text.setText(value); text.setTextSize(size); text.setTextColor(ink); return text; }
    private void toast(String message) { Toast.makeText(activity, message == null ? "请求失败" : message, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}

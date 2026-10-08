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
        titles.add("本机查重（自建库）"); ids.add("local");
        titles.add("联网查重（内置文献库）"); ids.add("web");
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
            if ("local".equals(id)) scan(false, false);
            else if ("web".equals(id)) scan(true, false);
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
        final ApiClient.Task task = begin(aigcOnly ? "AIGC 检测" : useWeb ? "联网查重" : "本机查重");
        final EngineSettings options = engine.copy();
        final boolean wantWeb = useWeb && options.web && !aigcOnly;
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
                limits.coreKey = options.coreKey;
                limits.proxy = options.proxy;
                ArrayList<String> engines = new ArrayList<String>();
                if (wantWeb) for (String id : options.engines) engines.add(id);
                final DuplicateEngine.Report result = DuplicateEngine.scan(selection, corpus, wantWeb,
                        engines, limits, task, new DuplicateEngine.Progress() {
                            public void step(String label, int done, int total) {
                                progress(total > 0 ? label + " " + done + "/" + total : label);
                            }
                        });
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
            if (result.retrievalPartial) box.addView(label(result.retrievalPartialReason, 11));
        } else if (result.retrievalIncomplete) {
            // A run that consulted nothing must not read as a clean document: 0.00% here would be
            // the most expensive number in the app to get wrong.
            box.addView(label("未完成查重", 20));
            box.addView(label(result.retrievalReason == null
                    ? "检索没有取回可比对的文献，相似度类指标无法成立" : result.retrievalReason, 13));
            box.addView(label("相似度类指标不成立，只有 AIGC 倾向是本机计算的结果。", 11));
        }
        /* 这一格与 HTML 报告同一份文案、同一把尺子（0.7.1）：档位 + 可疑字数 + 全文字数，没有百分号。
           0.7.0 这里印的 "AIGC 倾向 17.50%" 是字符加权句分冒充比例，与上一行的总相似度比不是一把尺。 */
        box.addView(label("机器生成倾向 " + DuplicateEngine.aigcTrend(result), 15));
        String aigcScore = DuplicateEngine.aigcScoreLine(result);
        if (!aigcScore.isEmpty())
            box.addView(label(DuplicateEngine.AIGC_SCORE_LABEL + " " + aigcScore, 11));
        box.addView(label(result.detectedAt + "  ·  用时 " + (result.elapsedMillis / 1000L) + " 秒", 11));
        for (String note : result.notes) box.addView(label("提示：" + note, 11));
        if (scanShowsDuplicates) for (final TextCorpus.Hit hit : result.hits) {
            TextView snippet = label(snippetText(hit), 14);
            snippet.setPadding(0, dp(10), 0, dp(10)); snippet.setTextIsSelectable(true);
            snippet.setOnClickListener(view -> navigateTo(hit.start, hit.end));
            box.addView(snippet);
        }
        if (result.aigc != null) {
            box.addView(label("AIGC 倾向较高的句子", 13));
            int shown = 0;
            for (final AigcDetector.Sentence sentence : result.aigc.sentences) {
                if (shown >= 20 || sentence.score < 0.4f) continue;
                shown++;
                String quote = lastScanned == null ? "" : safeSlice(lastScanned.text, sentence.start, sentence.end);
                TextView row = label(String.format(Locale.CHINA, "%.0f%%  ", sentence.score * 100f)
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
    private String snippetText(TextCorpus.Hit hit) {
        TextCorpus.Source source = hit.source;
        String title = source == null || source.title.isEmpty() ? "相似片段" : source.title;
        String year = source == null ? "" : source.year;
        return safeSlice(lastScanned == null ? "" : lastScanned.text, hit.start, hit.end)
                + "\n" + title + (year.isEmpty() ? "" : "（" + year + "）");
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
            for (int i = 0; i < uris.size(); i++) {
                if (task.cancelled()) break;
                progress("导入 " + (i + 1) + "/" + uris.size());
                try {
                    byte[] bytes = readAll(uris.get(i));
                    String error = library.addDocument(MainActivity.displayName(activity, uris.get(i)), bytes);
                    if (error == null) added++; else if (firstError.isEmpty()) firstError = error;
                } catch (Exception error) { if (firstError.isEmpty()) firstError = "读取失败"; }
            }
            final int count = added; final String tail = firstError.isEmpty() ? "" : "（" + firstError + "）";
            complete(task, () -> toast("已导入 " + count + " 篇" + tail));
        }, "wordlite-library"); worker.start();
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
        return "\n\n找过的路：" + Routes.candidateList(proxy)
                + "\n手机自己出不去时，把流量经电脑上的代理出去：检索设置里的\"HTTP 代理\"填电脑的地址与端口"
                + "（例如 192.168.1.20:7897，代理端需允许局域网连接）。USB 连着电脑时先执行"
                + " adb reverse tcp:7897 tcp:<电脑上的代理端口>（Clash Verge 默认监听 7897，未必是 7890）。"
                /* 反代进手机的端口是 7897 还是 7890 都行：这两个回环端口 Routes 都会自动发现，
                   所以这一格留空也用得上代理，不必让用户去猜电脑上那个端口是几号。 */
                + "反代进来之后这一格留空也能自动找到。";
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
        final EditText windows = field(String.valueOf(engine.windows), "检索窗口数（1-24）");
        final EditText core = field(engine.coreKey, "CORE API Key（可留空）");
        final EditText proxy = field(engine.proxy, "HTTP 代理 host:port（海外检索源需经电脑代理时填写，可留空）");
        box.addView(perEngine); box.addView(timeout); box.addView(windows); box.addView(core); box.addView(proxy);
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
                    value.coreKey = core.getText().toString().trim();
                    value.proxy = proxy.getText().toString().trim();
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

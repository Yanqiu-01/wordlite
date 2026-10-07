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
    private EngineSettings engine = new EngineSettings();
    private DuplicateEngine.Report lastScan;
    private TextSelection lastScanned;
    private boolean scanShowsDuplicates = true;
    private TextView jobLabel;

    public ApiWorkflow(Activity activity, Host host) {
        this.activity = activity; this.host = host; settings = new SettingsManager(activity);
        boolean dark = (activity.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        ink = dark ? 0xFFF2F2F2 : 0xFF202020; surface = dark ? 0xFF252525 : 0xFFF7F7F7;
        library = new LocalLibrary(new File(activity.getFilesDir(), "wordlite-library"));
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
        titles.add("自定义接口查重"); ids.add("api");
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
            else if ("api".equals(id)) customCheckMenu();
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
                limits.coreKey = options.coreKey;
                ArrayList<String> engines = new ArrayList<String>();
                if (wantWeb) for (String id : options.engines) engines.add(id);
                final DuplicateEngine.Report result = DuplicateEngine.scan(selection, corpus, wantWeb,
                        engines, limits, task, new DuplicateEngine.Progress() {
                            public void step(String label, int done, int total) {
                                progress(total > 0 ? label + " " + done + "/" + total : label);
                            }
                        });
                complete(task, () -> {
                    lastScan = result; lastScanned = selection; scanShowsDuplicates = !aigcOnly;
                    if (document == host.document()) applyScan(result, selection);
                    showScan();
                });
            } catch (Exception error) { fail(task, error); }
        }, "wordlite-scan"); worker.start();
    }
    private void applyScan(DuplicateEngine.Report result, TextSelection selection) {
        DocxDocument document = host.document();
        document.displayHighlights.clear();
        if (!selection.unchanged(document)) return;
        if (scanShowsDuplicates)
            for (TextCorpus.Hit hit : result.hits) addHighlight(selection, hit.start, hit.end, DUPLICATE_INK);
        if (result.aigc != null) for (AigcDetector.Sentence sentence : result.aigc.sentences)
            if (sentence.score >= 0.5f) addHighlight(selection, sentence.start, sentence.end, AIGC_INK);
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
        if (scanShowsDuplicates) {
            box.addView(label(String.format(Locale.CHINA, "总相似度比 %.2f%%", result.overallRate), 20));
            box.addView(label(String.format(Locale.CHINA, "去除引用重复比 %.2f%%  ·  自编率 %.2f%%",
                    result.excludingCitationsRate, result.selfWrittenRate), 13));
            if (!result.byEngine.isEmpty()) {
                StringBuilder line = new StringBuilder("来源分布：");
                for (Map.Entry<String, Double> entry : result.byEngine.entrySet())
                    line.append(engineTitle(entry.getKey())).append(' ')
                            .append(String.format(Locale.CHINA, "%.1f%%  ", entry.getValue()));
                box.addView(label(line.toString().trim(), 12));
            }
        }
        box.addView(label(String.format(Locale.CHINA, "AIGC 倾向 %.2f%%", result.aigcRate), 15));
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
        pendingReport = CheckReport.html(host.fileName(), lastScan);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/html"); intent.putExtra(Intent.EXTRA_TITLE,
                host.fileName().replaceAll("(?i)\\.docx$", "") + "-查重报告.html");
        activity.startActivityForResult(intent, REQUEST_REPORT);
    }
    private void libraryMenu() {
        new AlertDialog.Builder(activity).setTitle("自建库")
                .setItems(new String[]{"导入文档", "查看清单", "清空自建库"}, (dialog, which) -> {
                    if (which == 0) host.pickLibrary();
                    else if (which == 1) showLibrary();
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
        box.addView(perEngine); box.addView(timeout); box.addView(windows); box.addView(core);
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
        new AlertDialog.Builder(activity).setTitle("降重")
                .setItems(titles.toArray(new String[titles.size()]), (dialog, which) -> {
            String id = ids.get(which);
            if ("compare".equals(id)) compare();
            else if (id.startsWith("local-")) rewriteLocal(Integer.parseInt(id.substring(6)));
            else rewrite(Integer.parseInt(id.substring(4)));
        }).setNegativeButton("取消", null).show();
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

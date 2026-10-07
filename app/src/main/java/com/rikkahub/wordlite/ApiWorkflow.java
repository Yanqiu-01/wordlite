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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

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

    public ApiWorkflow(Activity activity, Host host) {
        this.activity = activity; this.host = host; settings = new SettingsManager(activity);
        boolean dark = (activity.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        ink = dark ? 0xFFF2F2F2 : 0xFF202020; surface = dark ? 0xFF252525 : 0xFFF7F7F7;
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
        String[] actions = lastCheck == null ? new String[]{"上传文档", "选中文本"}
                : new String[]{"上传文档", "选中文本", "查重结果", "导出报告", "清除高亮"};
        new AlertDialog.Builder(activity).setTitle("查重").setItems(actions, (dialog, which) -> {
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
    public void rewriteMenu() {
        if (job != null || host.document() == null) return;
        String[] scopes = rewrites.isEmpty() ? new String[]{"选中文本", "当前段落", "全文"}
                : new String[]{"选中文本", "当前段落", "全文", "改写建议"};
        new AlertDialog.Builder(activity).setTitle("降重").setItems(scopes, (dialog, which) -> {
            if (which == 3) compare(); else rewrite(which);
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
        ProgressBar progress = new ProgressBar(activity);
        progressDialog = new AlertDialog.Builder(activity).setTitle(title).setView(progress)
                .setNegativeButton("取消", (dialog, which) -> task.cancel()).create();
        progressDialog.setOnCancelListener(dialog -> task.cancel()); progressDialog.show(); return task;
    }
    private void complete(ApiClient.Task task, Runnable callback) {
        activity.runOnUiThread(() -> {
            if (job != task) return; job = null; worker = null; host.busy(false);
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

package com.rikkahub.wordlite;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.LinkedHashMap;
import java.util.Map;

/** Standard settings form; no API endpoints, secrets or network actions supplied by the app. */
public final class ApiSettingsActivity extends Activity {
    private final ApiConfig[] profiles = {new ApiConfig(), new ApiConfig()};
    private Spinner service, method, mode;
    private EditText url, key, headers, body, mapping, timeout, retries, textField, fileField, terms;
    private CheckBox fraction, codePoints;
    private int selected;
    private boolean binding;
    private ApiClient.Task testTask;
    private int ink;
    private SettingsManager settings;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        settings = new SettingsManager(this);
        boolean dark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        ink = dark ? 0xFFF2F2F2 : 0xFF202020;
        LinearLayout root = column(); root.setBackgroundColor(dark ? 0xFF252525 : 0xFFF7F7F7);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("‹"); back.setContentDescription("返回"); back.setOnClickListener(view -> finish()); top.addView(back);
        TextView title = label("API 设置"); top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button save = button("保存"); save.setOnClickListener(view -> save()); top.addView(save);
        root.addView(top);
        ScrollView scroll = new ScrollView(this); LinearLayout form = column(); form.setPadding(dp(16), dp(8), dp(16), dp(16)); scroll.addView(form);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        service = spinner(form, "接口", new String[]{"查重", "降重"});
        url = field(form, "API 地址", false); url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        key = field(form, "API Key", false); key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setSaveEnabled(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        method = spinner(form, "请求方式", new String[]{"POST", "GET", "PUT", "PATCH", "DELETE"});
        mode = spinner(form, "请求类型", new String[]{"JSON", "文本", "表单", "文档上传"});
        headers = field(form, "请求头（JSON）", true);
        body = field(form, "请求体模板", true);
        mapping = field(form, "响应字段映射（JSON）", true);
        textField = field(form, "文本字段", false); fileField = field(form, "文件字段", false);
        timeout = field(form, "超时（秒）", false); retries = field(form, "重试次数", false);
        timeout.setInputType(InputType.TYPE_CLASS_NUMBER); retries.setInputType(InputType.TYPE_CLASS_NUMBER);
        fraction = new CheckBox(this); fraction.setText("重复率使用 0–1"); fraction.setTextColor(ink); form.addView(fraction);
        codePoints = new CheckBox(this); codePoints.setText("片段位置按 Unicode 字符计数"); codePoints.setTextColor(ink); form.addView(codePoints);
        terms = field(form, "保留术语", true);
        Button test = button("测试连接"); test.setOnClickListener(view -> test()); form.addView(test);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        });
        setContentView(root);
        boolean failed = false;
        for (int i = 0; i < profiles.length; i++) {
            try { profiles[i] = settings.load(ApiConfig.Service.values()[i]); }
            catch (Exception error) { failed = true; }
        }
        if (failed) toast("设置读取失败");
        selected = getIntent().getIntExtra("service", 0) == 1 ? 1 : 0;
        service.setSelection(selected); bind();
        service.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (binding || position == selected) return;
                try { profiles[selected] = read(false); } catch (Exception error) { service.setSelection(selected); toast("配置格式无效"); return; }
                selected = position; bind();
            }
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
    }
    private void bind() {
        binding = true; ApiConfig config = profiles[selected];
        url.setText(config.url); key.setText(config.key);
        for (int i = 0; i < method.getCount(); i++) if (config.method.equals(method.getItemAtPosition(i))) method.setSelection(i);
        mode.setSelection(config.mode.ordinal()); headers.setText(ApiJson.stringify(config.headers)); body.setText(config.bodyTemplate);
        mapping.setText(ApiJson.stringify(config.mappings)); textField.setText(config.textField); fileField.setText(config.fileField);
        timeout.setText(String.valueOf(config.timeoutSeconds)); retries.setText(String.valueOf(config.retries));
        fraction.setChecked(config.rateFraction); codePoints.setChecked(config.codePointOffsets);
        StringBuilder text = new StringBuilder(); for (String term : config.terms) { if (text.length() > 0) text.append('\n'); text.append(term); }
        terms.setText(text); binding = false;
    }
    private ApiConfig read(boolean validate) {
        ApiConfig config = new ApiConfig(); config.url = url.getText().toString().trim(); config.key = key.getText().toString();
        config.method = String.valueOf(method.getSelectedItem()); config.mode = ApiConfig.Mode.values()[mode.getSelectedItemPosition()];
        map(headers.getText().toString(), config.headers); map(mapping.getText().toString(), config.mappings);
        config.bodyTemplate = body.getText().toString(); config.textField = textField.getText().toString().trim(); config.fileField = fileField.getText().toString().trim();
        config.timeoutSeconds = Integer.parseInt(timeout.getText().toString()); config.retries = Integer.parseInt(retries.getText().toString());
        config.rateFraction = fraction.isChecked(); config.codePointOffsets = codePoints.isChecked();
        for (String line : terms.getText().toString().split("\\r?\\n")) if (!line.trim().isEmpty()) config.terms.add(line.trim());
        if (validate) config.validate(); return config;
    }
    private static void map(String text, Map<String, String> target) {
        Object parsed = ApiJson.parse(text.trim().isEmpty() ? "{}" : text);
        if (!(parsed instanceof Map)) throw new IllegalArgumentException("配置格式无效");
        target.clear();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet()) {
            if (!(entry.getValue() instanceof String)) throw new IllegalArgumentException("配置格式无效");
            target.put(String.valueOf(entry.getKey()), (String) entry.getValue());
        }
    }
    private void save() {
        try {
            ApiConfig config = read(true); settings.save(ApiConfig.Service.values()[selected], config);
            profiles[selected] = config; toast("已保存");
        } catch (IllegalArgumentException error) { toast(error instanceof NumberFormatException ? "超时或重试次数无效" : error.getMessage()); }
        catch (Exception error) { toast("设置保存失败"); }
    }
    private void test() {
        final ApiConfig config;
        try { config = read(true); } catch (Exception error) { toast("配置无效"); return; }
        if (testTask != null) return;
        final ApiClient.Task task = new ApiClient.Task(); testTask = task;
        android.widget.ProgressBar progress = new android.widget.ProgressBar(this);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("测试连接").setView(progress)
                .setNegativeButton("取消", (d, which) -> task.cancel()).create();
        dialog.setOnCancelListener(d -> task.cancel()); dialog.show();
        new Thread(() -> {
            String message;
            try { ApiClient.execute(config, "Word Lite", null, "", task); message = "连接成功"; }
            catch (Exception error) { message = error instanceof ApiClient.Failure ? error.getMessage() : "连接失败"; }
            final String result = message;
            runOnUiThread(() -> {
                testTask = null;
                if (isFinishing() || isDestroyed()) return;
                dialog.dismiss(); if (!task.cancelled()) toast(result);
            });
        }, "word-api-test").start();
    }
    @Override protected void onDestroy() { if (testTask != null) testTask.cancel(); super.onDestroy(); }
    private EditText field(LinearLayout parent, String name, boolean multiline) {
        parent.addView(label(name)); EditText input = new EditText(this); input.setTextColor(ink); input.setTextSize(14);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        input.setMinLines(multiline ? 2 : 1); input.setSaveEnabled(false); input.setTag("setting-" + name);
        parent.addView(input, new LinearLayout.LayoutParams(-1, -2)); return input;
    }
    private Spinner spinner(LinearLayout parent, String name, String[] choices) {
        parent.addView(label(name)); Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, choices)); parent.addView(spinner); return spinner;
    }
    private TextView label(String value) { TextView text = new TextView(this); text.setText(value); text.setTextColor(ink); text.setTextSize(13); text.setPadding(0, dp(12), 0, dp(4)); return text; }
    private Button button(String text) { Button button = new Button(this); button.setText(text); button.setTextColor(ink); button.setTextSize(13); button.setAllCaps(false); return button; }
    private LinearLayout column() { LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); return box; }
    private void toast(String message) { Toast.makeText(this, message == null ? "配置无效" : message, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}

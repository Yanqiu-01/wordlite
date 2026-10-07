package com.rikkahub.wordlite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AlignmentSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ViewerActivity extends Activity {
    private static final int PAPER = 0xFFFFFEFB;
    private static final int INK = 0xFF25312E;
    private static final int MUTED = 0xFF6D7773;
    private static final int JADE = 0xFF397C73;
    private static final int AMBER = 0xFFB97718;
    private static final int RED = 0xFFB14B48;

    private Uri documentUri;
    private String documentName;
    private DocxDocument document;
    private DocxChecker.Profile profile;
    private LinearLayout page;
    private LinearLayout contentColumn;
    private ScrollView scrollView;
    private TextView toolbarTitle;
    private TextView issueSummary;
    private final HashMap<Integer, View> anchors = new HashMap<Integer, View>();
    private final HashSet<Integer> issueBlocks = new HashSet<Integer>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        DocxTextLayout.initialize(this);
        styleSystemBars();
        profile = readProfile();
        documentUri = getIntent().getData();
        if (documentUri == null) {
            Toast.makeText(this, "没有找到要打开的文档", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        documentName = MainActivity.displayName(this, documentUri);
        buildShell();
        parseInBackground();
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF4F3EE);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(9), dp(10), dp(10), dp(10));
        toolbar.setBackgroundColor(PAPER);
        Button back = smallButton("‹");
        back.setTextSize(28);
        back.setOnClickListener(v -> finish());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(45), dp(44)));
        toolbarTitle = text(documentName, 17, INK);
        toolbarTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        toolbarTitle.setSingleLine(true);
        toolbarTitle.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.leftMargin = dp(4);
        titleParams.rightMargin = dp(4);
        toolbar.addView(toolbarTitle, titleParams);
        Button rules = smallButton("规则");
        rules.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        rules.setOnClickListener(v -> showRulesDialog());
        toolbar.addView(rules, new LinearLayout.LayoutParams(dp(58), dp(42)));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        toolbar.setElevation(dp(2));
        View hair = new View(this);
        hair.setBackgroundColor(0x14000000);
        root.addView(hair, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2)));

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(16), dp(14), dp(34));
        scrollView.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scrollView, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        showLoading();
    }

    private void showLoading() {
        page.removeAllViews();
        LinearLayout card = card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(22), dp(34), dp(22), dp(34));
        ProgressBar progress = new ProgressBar(this);
        card.addView(progress, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView label = text("正在读取 OOXML 文档…", 15, MUTED);
        label.setPadding(0, dp(14), 0, 0);
        card.addView(label, wrap());
        page.addView(card, wrap());
    }

    private void parseInBackground() {
        final Uri uri = documentUri;
        final String name = documentName;
        new Thread(() -> {
            try {
                final DocxDocument parsed = DocxParser.parse(this, uri, name);
                DocxChecker.check(parsed, profile);
                runOnUiThread(() -> showDocument(parsed));
            } catch (final Throwable error) {
                runOnUiThread(() -> showError(error));
            }
        }, "docx-parser").start();
    }

    private void showError(Throwable error) {
        page.removeAllViews();
        LinearLayout card = card();
        card.setPadding(dp(20), dp(22), dp(20), dp(22));
        TextView heading = text("无法读取这个文档", 19, RED);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(heading, wrap());
        TextView detail = text(error == null || error.getMessage() == null
                ? "文件可能损坏，或不是标准 .docx 压缩包。" : error.getMessage(), 14, MUTED);
        detail.setPadding(0, dp(9), 0, 0);
        detail.setTextIsSelectable(true);
        card.addView(detail, wrap());
        TextView hint = text("请确认文件扩展名为 .docx，而不是旧版 .doc 或加密文档。原文件没有被修改。", 13, MUTED);
        hint.setPadding(0, dp(14), 0, 0);
        card.addView(hint, wrap());
        page.addView(card, wrap());
    }

    private void showDocument(DocxDocument parsed) {
        document = parsed;
        anchors.clear();
        issueBlocks.clear();
        for (DocxDocument.FormatIssue issue : document.issues) {
            if (issue.blockIndex >= 0) issueBlocks.add(issue.blockIndex);
        }
        getSharedPreferences("wordlite", MODE_PRIVATE).edit().putString("last_name", document.fileName).apply();
        page.removeAllViews();
        addSummaryCard();
        addIssuesCard();
        TextView documentHeading = text("正文预览", 19, INK);
        documentHeading.setTypeface(Typeface.DEFAULT_BOLD);
        documentHeading.setPadding(dp(3), dp(24), dp(3), dp(10));
        page.addView(documentHeading, wrap());
        contentColumn = new LinearLayout(this);
        contentColumn.setOrientation(LinearLayout.VERTICAL);
        contentColumn.setBackground(round(PAPER, 17));
        contentColumn.setPadding(dp(16), dp(18), dp(16), dp(24));
        contentColumn.setElevation(dp(2));
        page.addView(contentColumn, wrap());
        for (DocxDocument.Block block : document.blocks) {
            if (block instanceof DocxDocument.ParagraphBlock) {
                renderParagraph(contentColumn, (DocxDocument.ParagraphBlock) block, false);
            } else if (block instanceof DocxDocument.TableBlock) {
                renderTable(contentColumn, (DocxDocument.TableBlock) block);
            }
        }
        if (document.blocks.isEmpty()) {
            TextView empty = text("没有可显示的正文内容。", 15, MUTED);
            contentColumn.addView(empty, wrap());
        }
    }

    private void addSummaryCard() {
        LinearLayout summary = card();
        summary.setPadding(dp(18), dp(18), dp(18), dp(17));
        TextView title = text(document.title.length() == 0 ? document.fileName : document.title, 21, INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextIsSelectable(true);
        summary.addView(title, wrap());
        TextView filename = text(document.fileName, 12, MUTED);
        filename.setPadding(0, dp(6), 0, dp(12));
        summary.addView(filename, wrap());

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        stats.addView(chip(document.nonEmptyParagraphCount() + " 段", 0xFFE7F2EF), wrapWeight());
        stats.addView(chip(document.tableCount + " 表", 0xFFF3EBDD), marginWeight(dp(6)));
        stats.addView(chip(document.imageCount + " 图", 0xFFE9EEF4), marginWeight(dp(6)));
        summary.addView(stats, wrap());
        TextView compatibility = text("本地解析 · OOXML / Office 2024 常见 .docx", 12, JADE);
        compatibility.setPadding(0, dp(13), 0, 0);
        summary.addView(compatibility, wrap());
        page.addView(summary, wrap());
    }

    private void addIssuesCard() {
        LinearLayout card = card();
        card.setPadding(dp(18), dp(17), dp(18), dp(14));
        LinearLayout.LayoutParams params = wrap();
        params.topMargin = dp(12);
        TextView heading = text("格式检查", 19, INK);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(heading, wrap());
        int warnings = 0;
        int errors = 0;
        int infos = 0;
        for (DocxDocument.FormatIssue issue : document.issues) {
            if (issue.severity == DocxDocument.FormatIssue.ERROR) errors++;
            else if (issue.severity == DocxDocument.FormatIssue.WARNING) warnings++;
            else infos++;
        }
        issueSummary = text(summaryText(errors, warnings, infos), 13, MUTED);
        issueSummary.setPadding(0, dp(4), 0, dp(12));
        card.addView(issueSummary, wrap());
        TextView baseline = text(profileLabel(), 12, 0xFF7F8884);
        baseline.setPadding(0, 0, 0, dp(6));
        card.addView(baseline, wrap());
        for (DocxDocument.FormatIssue issue : document.issues) {
            card.addView(issueRow(issue), wrap());
        }
        page.addView(card, params);
    }

    private View issueRow(final DocxDocument.FormatIssue issue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(8), dp(9), dp(5), dp(9));
        row.setBackgroundColor(0xFFF9F8F3);
        TextView dot = text(issue.severity == DocxDocument.FormatIssue.ERROR ? "!"
                : issue.severity == DocxDocument.FormatIssue.WARNING ? "!" : "i", 12,
                issue.severity == DocxDocument.FormatIssue.ERROR ? RED
                        : issue.severity == DocxDocument.FormatIssue.WARNING ? AMBER : JADE);
        dot.setGravity(Gravity.CENTER);
        dot.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable dotBg = round(issue.severity == DocxDocument.FormatIssue.ERROR ? 0xFFF8E6E5
                : issue.severity == DocxDocument.FormatIssue.WARNING ? 0xFFFFF0D7 : 0xFFE3F1EE, 99);
        dot.setBackground(dotBg);
        row.addView(dot, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout copy = column();
        copy.setPadding(dp(10), 0, 0, 0);
        TextView title = text(issue.title, 14, INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        copy.addView(title, wrap());
        TextView detail = text(issue.detail, 12, MUTED);
        detail.setPadding(0, dp(3), 0, 0);
        detail.setTextIsSelectable(true);
        copy.addView(detail, wrap());
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
        if (issue.blockIndex >= 0) {
            row.setClickable(true);
            row.setOnClickListener(v -> scrollToBlock(issue.blockIndex));
            row.setForeground(selectableBackground());
        }
        return row;
    }

    private void renderParagraph(LinearLayout parent, DocxDocument.ParagraphBlock paragraph, boolean inTable) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(inTable ? dp(7) : dp(1), inTable ? dp(5) : dp(2), inTable ? dp(7) : dp(1), inTable ? dp(5) : dp(2));
        if (issueBlocks.contains(paragraph.index)) {
            GradientDrawable flagged = round(0xFFFFF8DC, 8);
            flagged.setStroke(dp(1), 0xFFF0D18B);
            wrapper.setBackground(flagged);
        }
        TextView textView = new TextView(this);
        textView.setTextColor(INK);
        textView.setTextIsSelectable(true);
        textView.setText(buildSpannedText(paragraph));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, PageGeometry.points(DocxTextLayout.defaultFontSizePoints(paragraph)));
        textView.setTypeface(DocxTextLayout.defaultTypeface(paragraph));
        textView.setIncludeFontPadding(true);
        textView.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
        applyParagraphLayout(textView, paragraph, inTable);
        wrapper.addView(textView, wrap());
        for (DocxDocument.EmbeddedImage image : paragraph.images) {
            ImageView imageView = imageView(image);
            if (imageView != null) {
                LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(-1, -2);
                imageParams.topMargin = dp(8);
                wrapper.addView(imageView, imageParams);
            }
        }
        parent.addView(wrapper, paragraphParams(paragraph, inTable));
        anchors.put(paragraph.index, wrapper);
    }

    private void renderTable(LinearLayout parent, DocxDocument.TableBlock table) {
        TextView label = text("表格", 12, MUTED);
        label.setPadding(dp(2), dp(14), dp(2), dp(5));
        parent.addView(label, wrap());
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setFillViewport(false);
        LinearLayout tableLayout = new LinearLayout(this);
        tableLayout.setOrientation(LinearLayout.VERTICAL);
        tableLayout.setBackgroundColor(0xFFD9DFDC);
        int columns = Math.max(1, table.columns);
        for (ArrayList<DocxDocument.Cell> row : table.rows) {
            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            for (DocxDocument.Cell cell : row) {
                LinearLayout cellLayout = new LinearLayout(this);
                cellLayout.setOrientation(LinearLayout.VERTICAL);
                cellLayout.setPadding(dp(1), dp(1), dp(1), dp(1));
                cellLayout.setBackgroundColor(0xFFD9DFDC);
                LinearLayout inner = new LinearLayout(this);
                inner.setOrientation(LinearLayout.VERTICAL);
                inner.setPadding(dp(6), dp(5), dp(6), dp(5));
                inner.setBackgroundColor(PAPER);
                for (DocxDocument.ParagraphBlock paragraph : cell.paragraphs) {
                    renderParagraph(inner, paragraph, true);
                }
                cellLayout.addView(inner, new LinearLayout.LayoutParams(dp(Math.max(118, 360 / columns)), -2));
                rowLayout.addView(cellLayout, new LinearLayout.LayoutParams(dp(Math.max(118, 360 / columns)), -2));
            }
            tableLayout.addView(rowLayout, wrap());
        }
        horizontal.addView(tableLayout, new HorizontalScrollView.LayoutParams(-2, -2));
        parent.addView(horizontal, wrap());
    }

    private ImageView imageView(DocxDocument.EmbeddedImage image) {
        if (image == null || image.bytes == null) return null;
        Bitmap bitmap = decodeSampled(image.bytes, 1400);
        if (bitmap == null) return null;
        ImageView view = new ImageView(this);
        view.setImageBitmap(bitmap);
        view.setAdjustViewBounds(true);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setContentDescription("文档图片");
        return view;
    }

    private Bitmap decodeSampled(byte[] bytes, int maxDimension) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        int largest = Math.max(bounds.outWidth, bounds.outHeight);
        while (largest / options.inSampleSize > maxDimension) options.inSampleSize *= 2;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private SpannableStringBuilder buildSpannedText(DocxDocument.ParagraphBlock paragraph) {
        // Keep the reader preview on the exact same Run/font/script path as the
        // paper paginator and editor. The old implementation used Android's
        // generic TypefaceSpan/SuperscriptSpan and therefore lost Times-like
        // Latin metrics and enlarged the entire line for scripts.
        SpannableStringBuilder builder = DocxTextLayout.styledText(paragraph, PageGeometry.points(1f));
        if (builder.length() == 0 && paragraph.images.isEmpty()) builder.append(" ");
        Layout.Alignment alignment = Layout.Alignment.ALIGN_NORMAL;
        if (paragraph.format.alignment == 1) alignment = Layout.Alignment.ALIGN_CENTER;
        else if (paragraph.format.alignment == 2) alignment = Layout.Alignment.ALIGN_OPPOSITE;
        builder.setSpan(new AlignmentSpan.Standard(alignment), 0, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        DocxTextLayout.applyParagraphLineSpacing(builder, paragraph, PageGeometry.points(1f));
        return builder;
    }

    private void applyParagraphLayout(TextView view, DocxDocument.ParagraphBlock paragraph, boolean inTable) {
        int before = pxFromTwips(paragraph.format.spacingBeforeTwips);
        int after = pxFromTwips(paragraph.format.spacingAfterTwips);
        view.setPadding(0, before, 0, after);
        view.setLineSpacing(0f, 1f);
        if (paragraph.format.alignment == 3 && android.os.Build.VERSION.SDK_INT >= 26) {
            view.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD);
        }
    }

    private LinearLayout.LayoutParams paragraphParams(DocxDocument.ParagraphBlock paragraph, boolean inTable) {
        LinearLayout.LayoutParams params = wrap();
        if (!inTable && paragraph.format.pageBreakBeforeSet && paragraph.format.pageBreakBefore) {
            View spacer = new View(this);
            // The actual spacer is not needed for scrolling, but reserve visual separation.
        }
        return params;
    }

    private void scrollToBlock(int blockIndex) {
        final View target = anchors.get(blockIndex);
        if (target == null) {
            Toast.makeText(this, "该检查项没有可定位的正文段落", Toast.LENGTH_SHORT).show();
            return;
        }
        target.post(() -> scrollView.smoothScrollTo(0, Math.max(0, target.getTop() - dp(18))));
    }

    private void showRulesDialog() {
        if (document == null) {
            Toast.makeText(this, "文档还在读取中", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout box = column();
        box.setPadding(dp(22), 0, dp(22), 0);
        CheckBox baseline = new CheckBox(this);
        baseline.setText("启用常用中文论文基准");
        baseline.setTextSize(15);
        baseline.setChecked(profile.useCommonChineseThesisBaseline);
        box.addView(baseline, wrap());

        TextView fontLabel = text("中文字体基准", 12, MUTED);
        fontLabel.setPadding(0, dp(8), 0, dp(3));
        box.addView(fontLabel, wrap());
        final android.widget.EditText font = new android.widget.EditText(this);
        font.setSingleLine(true);
        font.setText(profile.expectedFont);
        font.setHint("例如：宋体；留空则不检查");
        font.setTextSize(14);
        box.addView(font, wrap());

        TextView sizeLabel = text("正文主字号", 12, MUTED);
        sizeLabel.setPadding(0, dp(8), 0, dp(3));
        box.addView(sizeLabel, wrap());
        Spinner size = new Spinner(this);
        String[] values = new String[]{"小四 / 12 pt", "五号 / 10.5 pt", "四号 / 14 pt", "不按字号基准检查"};
        size.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, values));
        size.setSelection(sizePosition(profile.expectedSizeHalfPoints));
        box.addView(size, wrap());

        CheckBox line = new CheckBox(this);
        line.setText("检查显式 1.5 倍行距");
        line.setTextSize(14);
        line.setChecked(profile.checkLineSpacing);
        box.addView(line, wrap());
        CheckBox indent = new CheckBox(this);
        indent.setText("检查显式首行缩进（约两字符）");
        indent.setTextSize(14);
        indent.setChecked(profile.checkFirstLineIndent);
        box.addView(indent, wrap());

        TextView note = text("这是预检基准，不代表所有学校模板。关闭基准后，仍会检查空段、混用、页面尺寸和修订标记等结构性问题。", 12, MUTED);
        note.setPadding(0, dp(9), 0, 0);
        box.addView(note, wrap());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("检查规则")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("应用", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            profile.useCommonChineseThesisBaseline = baseline.isChecked();
            profile.expectedFont = font.getText().toString().trim();
            int selection = size.getSelectedItemPosition();
            profile.expectedSizeHalfPoints = selection == 3 ? -1 : new int[]{24, 21, 28}[Math.min(selection, 2)];
            profile.checkLineSpacing = line.isChecked();
            profile.checkFirstLineIndent = indent.isChecked();
            writeProfile(profile);
            DocxChecker.check(document, profile);
            dialog.dismiss();
            showDocument(document);
        }));
        dialog.show();
    }

    private int sizePosition(int halfPoints) {
        if (halfPoints == 21) return 1;
        if (halfPoints == 28) return 2;
        if (halfPoints <= 0) return 3;
        return 0;
    }

    private String profileLabel() {
        if (!profile.useCommonChineseThesisBaseline) return "当前仅做结构性预检 · 可点右上角“规则”调整";
        String size = profile.expectedSizeHalfPoints > 0 ? String.format(Locale.US, "%.1f pt", profile.expectedSizeHalfPoints / 2f) : "不限定字号";
        return "当前基准：" + (profile.expectedFont.length() == 0 ? "不限定字体" : profile.expectedFont)
                + "、" + size + " · 仅供预检参考";
    }

    private String summaryText(int errors, int warnings, int infos) {
        if (errors == 0 && warnings == 0 && infos <= 1) return "暂未发现明显问题";
        return errors + " 个错误 · " + warnings + " 个警告 · " + infos + " 个提示";
    }

    private DocxChecker.Profile readProfile() {
        SharedPreferences p = getSharedPreferences("wordlite", MODE_PRIVATE);
        DocxChecker.Profile profile = new DocxChecker.Profile();
        profile.useCommonChineseThesisBaseline = p.getBoolean("baseline", true);
        profile.expectedFont = p.getString("font", "宋体");
        profile.expectedSizeHalfPoints = p.getInt("size", 24);
        profile.checkLineSpacing = p.getBoolean("line", false);
        profile.checkFirstLineIndent = p.getBoolean("indent", false);
        return profile;
    }

    private void writeProfile(DocxChecker.Profile profile) {
        getSharedPreferences("wordlite", MODE_PRIVATE).edit()
                .putBoolean("baseline", profile.useCommonChineseThesisBaseline)
                .putString("font", profile.expectedFont)
                .putInt("size", profile.expectedSizeHalfPoints)
                .putBoolean("line", profile.checkLineSpacing)
                .putBoolean("indent", profile.checkFirstLineIndent)
                .apply();
    }

    private Button smallButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(JADE);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(selectableBackground());
        return button;
    }

    private TextView chip(String label, int color) {
        TextView view = text(label, 12, INK);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(7), dp(8), dp(7));
        view.setBackground(round(color, 99));
        return view;
    }

    private LinearLayout card() {
        LinearLayout layout = column();
        layout.setBackground(round(PAPER, 17));
        layout.setElevation(dp(2));
        return layout;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private Drawable selectableBackground() {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return getDrawable(value.resourceId);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams wrapWeight() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    private LinearLayout.LayoutParams marginWeight(int margin) {
        LinearLayout.LayoutParams params = wrapWeight();
        params.leftMargin = margin;
        return params;
    }

    private int pxFromTwips(int twips) {
        if (twips <= 0) return 0;
        return Math.round(twips / 1440f * getResources().getDisplayMetrics().densityDpi);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void styleSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(PAPER);
        window.setNavigationBarColor(0xFFF4F3EE);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }
}

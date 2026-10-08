package com.rikkahub.wordlite;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.InputStream;
import java.io.OutputStream;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.Layout;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AlignmentSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.text.style.StrikethroughSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** A deliberately small Word-like paper editor built around the OOXML model. */
public class EditorActivity extends Activity {
    private static final int REQUEST_SAVE = 71;
    private static final int REQUEST_PICK = 72;
    private static final int REQUEST_PDF = 73;
    private static final int REQUEST_LIBRARY = 75;
    private PdfExportOptions pendingPdf;
    private boolean busy;
    private static final int PAPER = 0xFFFFFEFB;
    private static final int INK = 0xFF25312E;
    private static final int MUTED = 0xFF6D7773;
    private static final int JADE = 0xFF397C73;
    private static final int PAGE_GAP = 0xFFE7E6E1;

    private Uri sourceUri;
    private String fileName;
    private DocxDocument document;
    private LinearLayout pageStack;
    private LinearLayout root;
    private TextView layoutNotice;
    private EditText activeEditor;
    private LinearLayout editPanel, editBody;
    private ZoomableScrollView pageScroll;
    private int totalPages = 1;
    private boolean building;
    private final HashMap<Integer, EditText> editors = new HashMap<Integer, EditText>();
    private final HashMap<Integer, Integer> pageByBlock = new HashMap<Integer, Integer>();
    private final ArrayList<UndoEntry> undoStack = new ArrayList<UndoEntry>();
    private final ArrayList<UndoEntry> redoStack = new ArrayList<UndoEntry>();

    private static final class UndoEntry {
        int blockIndex;
        String text;
        ArrayList<DocxDocument.Run> runs;
        DocxDocument.ParagraphFormat format;
        ArrayList<DocxDocument.Revision> revisions;
        ArrayList<DocxDocument.Comment> comments;
        ArrayList<DocxDocument.FieldCode> fields;
        ArrayList<DocxDocument.Hyperlink> hyperlinks;

    }
    private DocxChecker.Profile profile;
    private RibbonUI ribbon;
    private NavigationPane navigation;
    private TextView status;
    private LinearLayout workspaceBody;
    private LinearLayout checkPanel;
    private TextView bodyTab, checkTab;
    private boolean readingMode;
    private DocxDocument.EmbeddedImage selectedImage;
    private int selectedImageBlock = -1;
    private int viewInk = INK;
    private int surfaceColor = 0xFFF5F5F5;
    private int wordCount;
    private ReviewPanel reviewPanel;
    private int revisionCursor = -1;
    private ApiWorkflow api;

    private final HashMap<Integer, Integer> physicalPageByBlock = new HashMap<Integer, Integer>();


    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        DocxTextLayout.initialize(this);
        styleSystemBars();
        profile = new DocxChecker.Profile();
        sourceUri = getIntent().getData();
        if (sourceUri == null) {
            Toast.makeText(this, "没有找到要打开的文档", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        fileName = MainActivity.displayName(this, sourceUri);
        shell();
        api = new ApiWorkflow(this, new ApiWorkflow.Host() {
            public DocxDocument document() { return document; }
            public void sync() { syncAll(); }
            public DocxDocument.ParagraphBlock currentParagraph() {
                return activeEditor == null ? null : paragraphFor((Integer) activeEditor.getTag());
            }
            public int[] selectedRange() {
                if (activeEditor == null) return new int[]{0, 0};
                return new int[]{Math.max(0, Math.min(activeEditor.getSelectionStart(), activeEditor.getSelectionEnd())),
                        Math.max(0, Math.max(activeEditor.getSelectionStart(), activeEditor.getSelectionEnd()))};
            }
            public Uri source() { return sourceUri; }
            public String fileName() { return fileName; }
            public void busy(boolean value) { setBusy(value); }
            public void beforeRewrite(int paragraph) { pushUndo(paragraph); }
            public void changed() { renderDocument(); }
            public void navigate(int paragraph, int start, int end) {
                navigateReview(paragraph, start);
                if (activeEditor != null) activeEditor.setSelection(Math.min(start, activeEditor.length()), Math.min(end, activeEditor.length()));
            }
            public void pickLibrary() { pickLibraryDocuments(); }
        });
        load();
    }

    private void shell() {
        root = column();
        boolean dark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        viewInk = dark ? 0xFFF2F2F2 : INK;
        root.setBackgroundColor(dark ? 0xFF202020 : 0xFFE3E5E8);
        ribbon = new RibbonUI(this, fileName, this::ribbonCommand);
        root.addView(ribbon, wrap());
        boolean darkMode = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        surfaceColor = darkMode ? 0xFF1E1E1E : 0xFFF5F5F5;
        editPanel = column();
        editPanel.setBackgroundColor(surfaceColor);
        LinearLayout editHeader = new LinearLayout(this);
        editHeader.setGravity(Gravity.RIGHT);
        Button done = button("完成", 13);
        done.setTextColor(viewInk);
        done.setOnClickListener(v -> finishParagraphEditing());
        editHeader.addView(done, new LinearLayout.LayoutParams(dp(72), dp(36)));
        editPanel.addView(editHeader, wrap());
        ScrollView editScroll = new ScrollView(this);
        editBody = column();
        editBody.setPadding(dp(12), 0, dp(12), dp(6));
        editScroll.addView(editBody);
        editPanel.addView(editScroll, new LinearLayout.LayoutParams(-1, dp(150)));
        editPanel.setVisibility(View.GONE);
        root.addView(editPanel, wrap());
        layoutNotice = text("", 11, MUTED); layoutNotice.setVisibility(View.GONE);
        workspaceBody = new LinearLayout(this);
        boolean compact = getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density < 600;
        workspaceBody.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        navigation = new NavigationPane(this); navigation.setVisibility(View.GONE);
        workspaceBody.addView(navigation, compact ? new LinearLayout.LayoutParams(-1, dp(140)) : new LinearLayout.LayoutParams(dp(170), -1));
        pageScroll = new ZoomableScrollView(this);
        pageStack = column(); pageStack.setPadding(dp(12), dp(14), dp(12), dp(24));
        pageScroll.addView(pageStack);
        workspaceBody.addView(pageScroll, compact ? new LinearLayout.LayoutParams(-1, 0, 1) : new LinearLayout.LayoutParams(0, -1, 1));
        reviewPanel = new ReviewPanel(this); reviewPanel.setVisibility(View.GONE);
        workspaceBody.addView(reviewPanel, compact ? new LinearLayout.LayoutParams(-1, dp(210)) : new LinearLayout.LayoutParams(dp(260), -1));
        root.addView(workspaceBody, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout bottomBar = column();
        bottomBar.setBackgroundColor(surfaceColor);
        View line = new View(this);
        line.setBackgroundColor(darkMode ? 0xFF3A3A3A : 0xFFD6D6D6);
        bottomBar.addView(line, new LinearLayout.LayoutParams(-1, 1));

        checkPanel = new LinearLayout(this);
        checkPanel.setGravity(Gravity.CENTER_VERTICAL);
        checkPanel.setPadding(dp(4), 0, dp(4), 0);
        Button checkNow = button("查重", 13), aigc = button("AIGC", 13), rewrite = button("降重", 13),
                apiSettings = button("设置", 13);
        checkNow.setTextColor(viewInk); aigc.setTextColor(viewInk); rewrite.setTextColor(viewInk);
        apiSettings.setTextColor(viewInk);
        checkNow.setTag("bottom-check-now"); aigc.setTag("bottom-aigc"); rewrite.setTag("bottom-rewrite");
        apiSettings.setTag("bottom-api-settings");
        checkNow.setOnClickListener(v -> api.checkMenu());
        aigc.setOnClickListener(v -> api.aigc());
        rewrite.setOnClickListener(v -> api.rewriteMenu());
        apiSettings.setOnClickListener(v -> api.settings(ApiConfig.Service.CHECK));
        checkPanel.addView(checkNow, new LinearLayout.LayoutParams(0, dp(40), 1));
        checkPanel.addView(aigc, new LinearLayout.LayoutParams(0, dp(40), 1));
        checkPanel.addView(rewrite, new LinearLayout.LayoutParams(0, dp(40), 1));
        checkPanel.addView(apiSettings, new LinearLayout.LayoutParams(0, dp(40), 1));
        checkPanel.setVisibility(View.GONE);
        bottomBar.addView(checkPanel, new LinearLayout.LayoutParams(-1, dp(40)));

        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);
        bodyTab = button("正文", 13); checkTab = button("查重", 13);
        bodyTab.setTag("bottom-body"); checkTab.setTag("bottom-check");
        bodyTab.setGravity(Gravity.CENTER); checkTab.setGravity(Gravity.CENTER);
        bodyTab.setOnClickListener(v -> { selectBottomMode(false); setReading(false); });
        checkTab.setOnClickListener(v -> { selectBottomMode(true); setReading(false); });
        bottomRow.addView(bodyTab, new LinearLayout.LayoutParams(0, dp(40), 1));
        bottomRow.addView(checkTab, new LinearLayout.LayoutParams(0, dp(40), 1));
        bottomBar.addView(bottomRow, new LinearLayout.LayoutParams(-1, dp(40)));

        status = text("", 10, viewInk); status.setSingleLine(); status.setTag("document-status");
        status.setPadding(dp(12), dp(1), dp(12), dp(1));
        bottomBar.addView(status, new LinearLayout.LayoutParams(-1, dp(18)));
        root.addView(bottomBar, wrap());
        selectBottomMode(false);
        pageScroll.setListener((page, zoom) -> updateStatus());
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root); showLoading();
    }

    private void selectBottomMode(boolean checker) {
        checkPanel.setVisibility(checker ? View.VISIBLE : View.GONE);
        bodyTab.setTextColor(checker ? MUTED : JADE);
        checkTab.setTextColor(checker ? JADE : MUTED);
        bodyTab.setTypeface(checker ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
        checkTab.setTypeface(checker ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private void ribbonCommand(String id) {
        if ("back".equals(id)) { finish(); return; }
        if ("open".equals(id)) { startActivity(new Intent(this, MainActivity.class)); return; }
        if (document == null || busy) return;
        switch (id) {
            case "save": saveAs(); break;
            case "save-as": saveAs(); break;
            case "pdf": exportPdfDialog(); break;
            case "close": finish(); break;
            case "undo": undo(); break;
            case "redo": redo(); break;
            case "bold": toggleBold(); break;
            case "italic": toggleItalic(); break;
            case "underline": toggleUnderline(); break;
            case "font": chooseFont(); break;
            case "size": chooseSize(); break;
            case "color": chooseColor(); break;
            case "left": setAlignment(0); break;
            case "center": setAlignment(1); break;
            case "right": setAlignment(2); break;
            case "justify": setAlignment(3); break;
            case "spacing": chooseLineSpacing(); break;
            case "paragraph": showParagraphDialog(); break;
            case "comment": addComment(); break;
            case "review": showReview(); break;
            case "track": document.trackRevisions = !document.trackRevisions; updateStatus(); break;
            case "accept": decideRevision(true); break;
            case "reject": decideRevision(false); break;
            case "previous": moveRevision(-1); break;
            case "next": moveRevision(1); break;
            case "image": pickImage(); break;
            case "image-size": imageSize(); break;
            case "table": showTableDialog(); break;
            case "symbol": insertSymbol(); break;
            case "fields": showFields(); break;
            case "check": showCheck(); break;
            case "report-center": api.reportCenter(); break;
            case "outline":
                navigation.setVisibility(navigation.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                if (navigation.getVisibility() == View.VISIBLE) reviewPanel.setVisibility(View.GONE); break;
            case "reading": setReading(true); break;
            case "page-view": setReading(false); break;
            case "zoom-out": pageScroll.zoomBy(.8f); break;
            case "zoom-in": pageScroll.zoomBy(1.25f); break;
            case "zoom-reset": pageScroll.resetScale(); break;
            default: break;
        }
        updateStatus();
    }

    private void setReading(boolean value) {
        readingMode = value;
        if (value) finishParagraphEditing();
        ribbon.setVisibility(value ? View.GONE : View.VISIBLE);
        updateStatus();
    }

    private void updateStatus() {
        if (status == null || document == null) return;
        status.setText(String.format(Locale.CHINA, "  %d / %d 页  ·  %d 字  ·  中文  ·  %d%%",
                pageScroll.currentPage() + 1, totalPages, wordCount, Math.round(pageScroll.scaleFactor() * 100)) + (document.trackRevisions ? "  · 修订" : ""));
    }

    private void updateWordCount() {
        if (document == null) return;
        wordCount = 0;
        for (DocxDocument.ParagraphBlock paragraph : document.paragraphs) {
            boolean latinWord = false;
            for (int at = 0; at < paragraph.text.length(); at++) {
                char c = paragraph.text.charAt(at);
                if (c >= 0x3400 && c <= 0x9FFF) { wordCount++; latinWord = false; }
                else if (Character.isLetterOrDigit(c)) { if (!latinWord) wordCount++; latinWord = true; }
                else latinWord = false;
            }
        }
    }

    private void insertSymbol() {
        final EditText editor = currentEditor(); if (editor == null) return;
        String[] symbols = {"α", "β", "γ", "δ", "μ", "π", "Ω", "∑", "∫", "√", "∞", "≤", "≥", "≠", "±", "∂", "∇", "∈", "ℝ", "→", "₀", "₁", "₂", "²", "³"};
        new AlertDialog.Builder(this).setTitle("符号").setItems(symbols, (dialog, at) -> {
            int[] range = selectionOrParagraph(editor);
            int start = Math.max(0, editor.getSelectionStart()), end = Math.max(0, editor.getSelectionEnd());
            editor.getText().replace(Math.min(start, end), Math.max(start, end), symbols[at]);
            ribbon.setContext("公式工具");
        }).setNegativeButton("取消", null).show();
    }

    private void imageSize() {
        if (selectedImage == null) return;
        LinearLayout box = column();
        EditText width = new EditText(this), height = new EditText(this);
        width.setHint("宽度（厘米）"); height.setHint("高度（厘米）");
        width.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        height.setInputType(width.getInputType());
        width.setText(String.format(Locale.US, "%.2f", selectedImage.widthEmu / 360000f));
        height.setText(String.format(Locale.US, "%.2f", selectedImage.heightEmu / 360000f));
        box.addView(width); box.addView(height);
        new AlertDialog.Builder(this).setTitle("图片大小").setView(box).setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> {
                    try {
                        float w = Float.parseFloat(width.getText().toString()), h = Float.parseFloat(height.getText().toString());
                        if (w <= 0 || h <= 0 || w > 100 || h > 100) throw new IllegalArgumentException();
                        selectedImage.widthEmu = Math.round(w * 360000); selectedImage.heightEmu = Math.round(h * 360000);
                        DocxDocument.ParagraphBlock p = paragraphFor(selectedImageBlock); if (p != null) p.textEdited = true;
                        renderDocument();
                    } catch (Exception error) { Toast.makeText(this, "尺寸无效", Toast.LENGTH_SHORT).show(); }
                }).show();
    }

    @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (event.isCtrlPressed()) {
            switch (keyCode) {
                case android.view.KeyEvent.KEYCODE_S: saveAs(); return true;
                case android.view.KeyEvent.KEYCODE_Z: if (event.isShiftPressed()) redo(); else undo(); return true;
                case android.view.KeyEvent.KEYCODE_Y: redo(); return true;
                case android.view.KeyEvent.KEYCODE_B: toggleBold(); return true;
                case android.view.KeyEvent.KEYCODE_I: toggleItalic(); return true;
                case android.view.KeyEvent.KEYCODE_U: toggleUnderline(); return true;
                case android.view.KeyEvent.KEYCODE_M: if (event.isAltPressed()) { addComment(); return true; } break;
            }
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
            if (readingMode) setReading(false); else finishParagraphEditing(); return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showLoading() {
        pageStack.removeAllViews();
        LinearLayout card = column();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(20), dp(42), dp(20), dp(42));
        card.setBackground(round(PAPER, 15));
        ProgressBar progress = new ProgressBar(this);
        card.addView(progress, new LinearLayout.LayoutParams(dp(42), dp(42)));
        pageStack.addView(card, wrap());
    }

    private void load() {
        final Uri uri = sourceUri;
        new Thread(() -> {
            try {
                final DocxDocument parsed = DocxParser.parse(this, uri, fileName);
                runOnUiThread(() -> {
                    document = parsed;
                    document.reviewAuthor = getSharedPreferences("wordlite", MODE_PRIVATE).getString("review_author", "作者");
                    getSharedPreferences("wordlite", MODE_PRIVATE).edit()
                            .putString("last_name", fileName).putString("last_uri", uri.toString()).apply();
                    renderDocument();
                });
            } catch (final Throwable error) {
                runOnUiThread(() -> showLoadError(error));
            }
        }, "word-docx-open").start();
    }

    private void showLoadError(Throwable error) {
        pageStack.removeAllViews();
        LinearLayout card = column();
        card.setPadding(dp(20), dp(22), dp(20), dp(22));
        card.setBackground(round(PAPER, 15));
        TextView title = text("无法打开文档", 19, 0xFFB14B48);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title, wrap());
        TextView detail = text(error == null || error.getMessage() == null ? "文件可能损坏或格式不受支持。" : error.getMessage(), 14, MUTED);
        detail.setPadding(0, dp(9), 0, 0);
        detail.setTextIsSelectable(true);
        card.addView(detail, wrap());
        pageStack.addView(card, wrap());
    }

    private void renderDocument() {
        building = true;
        try {
            activeEditor = null;
            editors.clear();
            editBody.removeAllViews();
            editPanel.setVisibility(View.GONE);
            pageByBlock.clear(); physicalPageByBlock.clear();
            pageStack.removeAllViews();
            A4Paginator.PageResult result = new A4Paginator(document.section).paginate(document);
            totalPages = result.totalPages();
            if (layoutNotice != null) layoutNotice.setVisibility(View.GONE);
            for (int i = 0; i < result.pages.size(); i++) {
                A4Paginator.PageContent page = result.pages.get(i);
                for (A4Paginator.ParagraphLayout fragment : page.paragraphs) {
                    int displayedPage = page.displayedPageNumber > 0 ? page.displayedPageNumber : i + 1;
                    if (!pageByBlock.containsKey(fragment.blockIndex)) pageByBlock.put(fragment.blockIndex, displayedPage);
                    if (!physicalPageByBlock.containsKey(fragment.blockIndex)) physicalPageByBlock.put(fragment.blockIndex, i);
                    if (fragment.row != null) for (A4Paginator.CellParagraph cp : fragment.row.paragraphs)
                        if (!pageByBlock.containsKey(cp.text.source.index)) pageByBlock.put(cp.text.source.index, displayedPage);
                    if (fragment.row != null) for (A4Paginator.CellParagraph cp : fragment.row.paragraphs)
                        if (!physicalPageByBlock.containsKey(cp.text.source.index)) physicalPageByBlock.put(cp.text.source.index, i);
                }
                PaperPageView paper = new PaperPageView(this, document, page,
                        page.geometry == null ? result.geometry : page.geometry,
                        i + 1, totalPages, new PaperPageView.Listener() {
                            @Override public void edit(int index, int offset) {
                                if (!readingMode) openParagraphEditor(index, offset);
                            }
                            @Override public void comment(DocxDocument.Comment comment) {
                                if (busy) return;
                                bindReview(); navigation.setVisibility(View.GONE); reviewPanel.setVisibility(View.VISIBLE);
                            }
                            @Override public void image(int index, DocxDocument.EmbeddedImage image) {
                                selectedImage = image; selectedImageBlock = index;
                                ribbon.setContext("图片工具"); ribbon.select("图片工具");
                            }
                        });
                paper.setTag("page-" + i);
                paper.setElevation(dp(2));
                LinearLayout.LayoutParams params = wrap();
                params.bottomMargin = dp(12);
                pageStack.addView(paper, params);
            }
        } catch (Exception e) {
            showLoadError(e);
        } finally { building = false; }
        navigation.bind(document, surfaceColor, viewInk, index -> {
            Integer target = physicalPageByBlock.get(index);
            if (target != null) pageScroll.scrollToPage(target);
        });
        updateWordCount(); bindReview();
        updateStatus();
    }

    private void openParagraphEditor(int index, int offset) {
        syncAll();
        DocxDocument.ParagraphBlock p = paragraphFor(index);
        if (p == null || busy) return;
        ribbon.setContext(TableOps.locate(document, index) != null ? "表格工具" : "");
        selectedImage = null;
        building = true;
        try {
            activeEditor = null;
            editors.clear(); editBody.removeAllViews();
            editPanel.setVisibility(View.VISIBLE);
            addParagraphEditor(editBody, p, pageByBlock.containsKey(index) ? pageByBlock.get(index) : 1, totalPages);
            activeEditor = editors.get(index);
            activeEditor.requestFocus();
            activeEditor.setSelection(Math.max(0, Math.min(offset, activeEditor.length())));
        } finally { building = false; }
    }

    private void finishParagraphEditing() {
        syncAll();
        android.view.inputmethod.InputMethodManager keyboard =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null && activeEditor != null) keyboard.hideSoftInputFromWindow(activeEditor.getWindowToken(), 0);
        renderDocument();
    }

    /** Pixels per typographic point matching the paragraph's own section width. */
    private float editorPointScale() {
        DocxDocument.ParagraphBlock paragraph = null;
        if (activeEditor != null && activeEditor.getTag() instanceof Integer)
            paragraph = paragraphFor((Integer) activeEditor.getTag());
        return editorPointScale(paragraph);
    }

    private float editorPointScale(DocxDocument.ParagraphBlock paragraph) {
        float displayWidth = getResources().getDisplayMetrics().widthPixels - dp(24);
        DocxDocument.SectionSettings section = document == null ? new DocxDocument.SectionSettings() : document.section;
        if (paragraph != null && document != null && !document.sections.isEmpty()) {
            int index = Math.max(0, Math.min(paragraph.sectionIndex, document.sections.size() - 1));
            section = document.sections.get(index);
        }
        PageGeometry geometry = new PageGeometry(section);
        return PageGeometry.points(1f) * geometry.fitScale(displayWidth);
    }

    private void addParagraphEditor(LinearLayout parent, DocxDocument.ParagraphBlock paragraph, int page, int total) {
        EditText editor = new EditText(this);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        float editorPixelsPerPoint = editorPointScale(paragraph);
        editor.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                DocxTextLayout.defaultFontSizePoints(paragraph) * editorPixelsPerPoint);
        editor.setTypeface(DocxTextLayout.defaultTypeface(paragraph));
        editor.setTextColor(viewInk);
        editor.setIncludeFontPadding(false);
        editor.setBackgroundColor(Color.TRANSPARENT);
        editor.setPadding(0, 0, 0, 0);
        editor.setSingleLine(false);
        if (android.os.Build.VERSION.SDK_INT >= 23) editor.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        editor.setText(buildSpanned(paragraph, page, total));
        applyEditorParagraphFormat(editor, paragraph);
        editors.put(paragraph.index, editor);
        editor.setTag(paragraph.index);
        editor.setOnFocusChangeListener((v, focus) -> { if (focus) activeEditor = (EditText) v; });
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (!building) pushUndo(paragraph.index);
            }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!building) {
                    ReviewManager.recordEdit(document, paragraph, start, before, s.subSequence(start, start + count).toString());
                    paragraph.textEdited = true;
                }
            }
            @Override public void afterTextChanged(Editable s) {
                if (!building) { syncParagraph(paragraph, editor); updateWordCount(); updateStatus(); }
            }
        });
        parent.addView(editor, wrap());
    }

    private SpannableStringBuilder buildSpanned(DocxDocument.ParagraphBlock paragraph, int page, int total) {
        float scale = editorPointScale(paragraph);
        SpannableStringBuilder text = DocxTextLayout.styledText(paragraph, scale);
        DocxTextLayout.applyParagraphLineSpacing(text, paragraph, scale);
        return text;
    }

    private void applyEditorParagraphFormat(EditText editor, DocxDocument.ParagraphBlock paragraph) {
        editor.setGravity(Gravity.TOP | gravityFor(paragraph.format.alignment));
        if (android.os.Build.VERSION.SDK_INT >= 26)
            editor.setJustificationMode(paragraph.format.alignment == 3 ? Layout.JUSTIFICATION_MODE_INTER_WORD : Layout.JUSTIFICATION_MODE_NONE);
        // The LineHeightSpan in buildSpanned is the source of truth for auto,
        // atLeast and exact Word spacing. Do not multiply it a second time here.
        editor.setLineSpacing(0, 1f);
    }

    private void toggleBold() { toggleStyle(Typeface.BOLD); }
    private void toggleItalic() { toggleStyle(Typeface.ITALIC); }

    private void toggleStyle(int wanted) {
        EditText editor = currentEditor();
        if (editor == null) return;
        Spannable text = editor.getText();
        int[] range = selectionOrParagraph(editor);
        recordRunFormat(editor, range);
        boolean already = range[1] > range[0];
        for (int at = range[0]; at < range[1]; at++) {
            DocxDocument.RunStyle current = styleAt(text, at);
            already &= wanted == Typeface.BOLD ? current.bold : current.italic;
        }
        DocxDocument.RunStyle overlay = new DocxDocument.RunStyle();
        if (wanted == Typeface.BOLD) { overlay.boldSet = true; overlay.bold = !already; }
        else { overlay.italicSet = true; overlay.italic = !already; }
        DocxTextLayout.applyStyle(text, range[0], range[1], overlay, editorPointScale());
        syncParagraph(paragraphFor((Integer) editor.getTag()), editor);
        editor.requestFocus();
    }

    private void toggleUnderline() {
        EditText editor = currentEditor();
        if (editor == null) return;
        Spannable text = editor.getText();
        int[] range = selectionOrParagraph(editor);
        recordRunFormat(editor, range);
        boolean already = range[1] > range[0];
        for (int at = range[0]; at < range[1]; at++) already &= styleAt(text, at).underline;
        DocxDocument.RunStyle overlay = new DocxDocument.RunStyle(); overlay.underlineSet = true; overlay.underline = !already;
        DocxTextLayout.applyStyle(text, range[0], range[1], overlay, editorPointScale());
        syncParagraph(paragraphFor((Integer) editor.getTag()), editor);
    }

    private void chooseSize() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("例如 12");
        input.setText("12");
        new AlertDialog.Builder(this).setTitle("设置字号（pt）").setView(input)
                .setNegativeButton("取消", null).setPositiveButton("应用", (d, which) -> {
                    try {
                        float value = Float.parseFloat(input.getText().toString());
                        EditText editor = currentEditor();
                        if (editor == null) return;
                        Spannable text = editor.getText();
                        int[] range = selectionOrParagraph(editor);
                        if (!(value >= 1 && value <= 400)) throw new IllegalArgumentException("字号范围为 1–400 pt");
                        recordRunFormat(editor, range);
                        DocxDocument.RunStyle style = new DocxDocument.RunStyle();
                        style.fontSizeHalfPoints = Math.round(value * 2);
                        DocxTextLayout.applyStyle(text, range[0], range[1], style, editorPointScale());
                    } catch (Exception ignored) { }
                }).show();
    }

    private void chooseFont() {
        final java.util.ArrayList<DocxFonts.Used> used = DocxFonts.usedIn(document);
        // 稿子用过的脸排最前面：手机上来回翻 27 个名字比在电脑上是另一回事。
        final String[] names = DocxFontAssets.orderForDocument(usedFamilies(used));
        /* 说明和列表分开放。AlertDialog 的 setMessage 和 setItems 同时给，EMUI 上只渲染说明、
           列表整个消失，所以这里自建一个可滚动视图：先一行"本文用了哪些字体、各自拿什么显示"，
           再一行一个候选字体，每一行用它自己那张脸写自己——不用先选中再回去看效果。 */
        final LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        TextView report = new TextView(this);
        report.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        report.setTextColor(0xFF757575);
        report.setPadding(dp(20), dp(10), dp(20), dp(6));
        report.setText(DocxFonts.summary(used));
        rows.addView(report);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);
        final AlertDialog dialog = new AlertDialog.Builder(this).setTitle("字体")
                .setView(scroll).setNegativeButton("取消", null).create();
        String current = null;
        EditText editor = currentEditor();
        if (editor != null) {
            int[] range = selectionOrParagraph(editor);
            DocxDocument.RunStyle at = styleAt(editor.getText(), range[0]);
            current = at == null ? null : at.eastAsiaFontFamily;
        }
        for (int i = 0; i < names.length; i++) {
            final String family = names[i];
            final String facePath = DocxFontAssets.pathFor(family);
            // 一行两行字：字体名用它自己那张脸写，"带了多少字"用系统的脸写（装饰脸的笔画认不得数字）。
            final LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.VERTICAL);
            line.setPadding(dp(20), dp(12), dp(20), dp(12));
            final TextView row = new TextView(this);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            row.setTextColor(0xFF1A1A1A);
            String face = DocxFontAssets.label(facePath);
            boolean currentRow = family.equals(current);
            // 名字写法不同不等于换了脸（MS Gothic 就是ＭＳ ゴシック），只有 substitution 说话才算替代。
            boolean own = DocxFontAssets.substitution(family).isEmpty();
            row.setText((currentRow ? "✓ " : "") + (own || face.equals(family)
                    ? family : family + "（字库没有，用" + face + "）"));
            Typeface loaded = FontManager.load(facePath);
            if (loaded != null) row.setTypeface(loaded);
            /* 这一行回答"你到底留了什么字"：数由 tools/build-font-coverage.py 从随包字库量出来，
               tests/FontSubstitution 第 12 段拿 java.awt 复量过一遍，所以它不许漂。 */
            String coverage = DocxFontCoverage.detail(facePath);
            if (coverage.length() > 0) {
                TextView detail = new TextView(this);
                detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                detail.setTextColor(0xFF8A8A8A);
                detail.setText(own ? coverage : face + "：" + coverage);
                line.addView(detail);
            }
            line.addView(row, 0);
            line.setBackgroundResource(currentRow ? android.R.drawable.list_selector_background : 0);
            line.setOnClickListener(v -> {
                applyFontFamily(family);
                dialog.dismiss();
            });
            rows.addView(line);
        }
        dialog.show();
    }

    /** 把字体名写到选区（没选区就整段）上，五种字体槽一起改，和 Word 的字体下拉一致。 */
    /** 面板排序只看"这篇稿子用过哪些字体名"，顺序就是它们出现的先后。 */
    private static java.util.ArrayList<String> usedFamilies(java.util.ArrayList<DocxFonts.Used> used) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        for (DocxFonts.Used one : used) out.add(one.family);
        return out;
    }

    private void applyFontFamily(String family) {
        EditText editor = currentEditor();
        if (editor == null) return;
        Spannable text = editor.getText();
        int[] range = selectionOrParagraph(editor);
        recordRunFormat(editor, range);
        DocxDocument.RunStyle style = new DocxDocument.RunStyle();
        style.fontFamily = style.asciiFontFamily = style.highAnsiFontFamily
                = style.eastAsiaFontFamily = style.complexScriptFontFamily = family;
        DocxTextLayout.applyStyle(text, range[0], range[1], style, editorPointScale());
    }
    private void chooseColor() {
        final String[] names = {"黑色", "红色", "蓝色", "绿色", "灰色", "橙色", "紫色"};
        final int[] colors = {0xFF000000, 0xFFFF0000, 0xFF0000FF, 0xFF008000, 0xFF808080, 0xFFFF8C00, 0xFF800080};
        new AlertDialog.Builder(this).setTitle("字体颜色").setItems(names, (d, which) -> {
            EditText editor = currentEditor();
            if (editor == null) return;
            Spannable text = editor.getText();
            int[] range = selectionOrParagraph(editor);
            recordRunFormat(editor, range);
            DocxDocument.RunStyle overlay = new DocxDocument.RunStyle(); overlay.colorSet = true; overlay.color = colors[which];
            DocxTextLayout.applyStyle(text, range[0], range[1], overlay, editorPointScale());
            syncParagraph(paragraphFor((Integer) editor.getTag()), editor);
        }).show();
    }

    private void chooseLineSpacing() {
        final String[] names = {"单倍", "1.15倍", "1.5倍", "2倍", "固定值20pt", "固定值28pt"};
        final float[] multipliers = {1f, 1.15f, 1.5f, 2f, -1f, -1f};
        final int[] fixedTwips = {0, 0, 0, 0, 400, 560}; // 20pt=400twips, 28pt=560twips
        new AlertDialog.Builder(this).setTitle("行距").setItems(names, (d, which) -> {
            EditText editor = currentEditor();
            if (editor == null) return;
            DocxDocument.ParagraphBlock paragraph = paragraphFor((Integer) editor.getTag());
            if (paragraph == null) return;
            recordParagraphFormat(paragraph);
            if (multipliers[which] > 0) {
                paragraph.format.lineSpacingTwips = Math.round(multipliers[which] * 240);
                paragraph.format.lineRule = "auto";
            } else {
                paragraph.format.lineSpacingTwips = fixedTwips[which];
                paragraph.format.lineRule = "exact";
            }
            syncParagraph(paragraph, editor);
            int start = editor.getSelectionStart(), end = editor.getSelectionEnd();
            boolean wasBuilding = building;
            building = true;
            try {
                editor.setText(buildSpanned(paragraph, 1, totalPages));
                editor.setSelection(Math.max(0, start), Math.max(0, end));
                applyEditorParagraphFormat(editor, paragraph);
            } finally { building = wasBuilding; }
        }).show();
    }

    private void setAlignment(int alignment) {
        EditText editor = currentEditor();
        if (editor == null) return;
        DocxDocument.ParagraphBlock paragraph = paragraphFor((Integer) editor.getTag());
        if (paragraph != null) { recordParagraphFormat(paragraph); paragraph.format.alignment = alignment; }
        if (paragraph != null) applyEditorParagraphFormat(editor, paragraph);
    }

    private void showParagraphDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("首行缩进 pt，例如 24");
        new AlertDialog.Builder(this).setTitle("首行缩进（pt）").setView(input)
                .setNegativeButton("取消", null).setPositiveButton("应用", (d, w) -> {
                    try {
                        float pt = Float.parseFloat(input.getText().toString());
                        EditText editor = currentEditor();
                        if (editor == null) return;
                        DocxDocument.ParagraphBlock paragraph = paragraphFor((Integer) editor.getTag());
                        if (paragraph != null && (!Float.isNaN(pt) && !Float.isInfinite(pt)) && Math.abs(pt) <= 400) {
                            recordParagraphFormat(paragraph);
                            paragraph.format.firstLineIndentTwips = Math.round(pt * 20);

                        }
                    } catch (Exception ignored) { }
                }).show();
    }

    private void commentTool() {
        EditText editor = currentEditor();
        if (editor != null && editor.getSelectionStart() != editor.getSelectionEnd()) addComment();
        else showComments();
    }

    private void addComment() {
        EditText editor = currentEditor();
        if (editor == null || editor.getSelectionStart() == editor.getSelectionEnd()) {
            Toast.makeText(this, "请先在正文中选择一段文字", Toast.LENGTH_SHORT).show();
            return;
        }
        final int start = Math.min(editor.getSelectionStart(), editor.getSelectionEnd());
        final int end = Math.max(editor.getSelectionStart(), editor.getSelectionEnd());
        final DocxDocument.ParagraphBlock paragraph = paragraphFor((Integer) editor.getTag());
        if (paragraph == null) return;
        EditText input = new EditText(this);
        input.setHint("写下批注");
        input.setMinLines(3);
        input.setGravity(Gravity.TOP);
        new AlertDialog.Builder(this).setTitle("新建批注").setView(input)
                .setNegativeButton("取消", null).setPositiveButton("保存", (d, w) -> {
                    if (input.getText().toString().trim().isEmpty()) return;
                    ReviewManager.comment(document, paragraph.index, start, end, input.getText().toString());
                    bindReview(); navigation.setVisibility(View.GONE); reviewPanel.setVisibility(View.VISIBLE);
                    for (int i = 0; i < pageStack.getChildCount(); i++) pageStack.getChildAt(i).invalidate();
                }).show();
    }

    private void showComments() { showReview(); }
    private void showReview() {
        syncAll(); bindReview(); navigation.setVisibility(View.GONE);
        reviewPanel.setVisibility(reviewPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
    }
    private void bindReview() {
        if (document == null || reviewPanel == null) return;
        reviewPanel.bind(document, surfaceColor, viewInk, new ReviewPanel.Listener() {
            @Override public void navigate(int paragraph, int offset) { navigateReview(paragraph, offset); }
            @Override public void decide(DocxDocument.Revision revision, boolean accept) {
                if (busy) return;
                syncAll(); pushUndo(revision.paragraphIndex); ReviewManager.decide(document, revision, accept);
                renderDocument(); bindReview();
            }
            @Override public void reply(DocxDocument.Comment comment) { replyComment(comment); }
            @Override public void resolve(DocxDocument.Comment comment) {
                comment.resolved = !comment.resolved; bindReview();
                for (int i = 0; i < pageStack.getChildCount(); i++) pageStack.getChildAt(i).invalidate();
            }
            @Override public void author() { chooseAuthor(); }
        });
    }
    private void chooseAuthor() {
        EditText input = new EditText(this); input.setText(document.reviewAuthor);
        new AlertDialog.Builder(this).setTitle("作者").setView(input).setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> {
                    if (!input.getText().toString().trim().isEmpty()) {
                        document.reviewAuthor = input.getText().toString().trim();
                        getSharedPreferences("wordlite", MODE_PRIVATE).edit().putString("review_author", document.reviewAuthor).apply();
                    }
                }).show();
    }
    private void replyComment(DocxDocument.Comment comment) {
        EditText input = new EditText(this); input.setMinLines(2);
        new AlertDialog.Builder(this).setTitle("回复").setView(input).setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    if (!input.getText().toString().trim().isEmpty()) {
                        ReviewManager.reply(document, comment, input.getText().toString()); bindReview();
                    }
                }).show();
    }
    private void navigateReview(int index, int offset) {
        Integer page = physicalPageByBlock.get(index); if (page != null) pageScroll.scrollToPage(page);
        openParagraphEditor(index, offset);
    }
    private ArrayList<DocxDocument.Revision> filteredRevisions() {
        ArrayList<DocxDocument.Revision> result = new ArrayList<DocxDocument.Revision>();
        String filter = reviewPanel == null ? "" : reviewPanel.authorFilter();
        for (DocxDocument.Revision revision : document.revisions)
            if (filter.isEmpty() || revision.author.equals(filter)) result.add(revision);
        return result;
    }
    private void moveRevision(int step) {
        ArrayList<DocxDocument.Revision> items = filteredRevisions(); if (items.isEmpty()) return;
        revisionCursor = revisionCursor < 0 ? (step < 0 ? items.size() - 1 : 0)
                : (revisionCursor + step + items.size()) % items.size();
        DocxDocument.Revision revision = items.get(revisionCursor); navigateReview(revision.paragraphIndex, revision.start);
    }
    private void decideRevision(boolean accept) {
        syncAll(); ArrayList<DocxDocument.Revision> items = filteredRevisions(); if (items.isEmpty()) return;
        revisionCursor = Math.max(0, Math.min(revisionCursor, items.size() - 1));
        DocxDocument.Revision revision = items.get(revisionCursor); pushUndo(revision.paragraphIndex);
        ReviewManager.decide(document, revision, accept); renderDocument(); bindReview();
    }
    private void recordRunFormat(EditText editor, int[] range) {
        DocxDocument.ParagraphBlock paragraph = paragraphFor((Integer) editor.getTag()); if (paragraph == null) return;
        syncParagraph(paragraph, editor); pushUndo(paragraph.index);
        ReviewManager.format(document, paragraph, range[0], range[1]);
    }
    private void recordParagraphFormat(DocxDocument.ParagraphBlock paragraph) {
        pushUndo(paragraph.index); ReviewManager.paragraphFormat(document, paragraph);
    }

    private void showFields() {
        LinearLayout box = column();
        box.setPadding(dp(18), 0, dp(18), 0);
        int count = 0;
        for (DocxDocument.ParagraphBlock paragraph : document.paragraphs) {
            for (DocxDocument.FieldCode field : paragraph.fields) {
                count++;
                box.addView(text(field.instruction + "  →  " + DocxFieldEngine.evaluate(document, field.instruction, pageByBlock.containsKey(paragraph.index) ? pageByBlock.get(paragraph.index) : 1, totalPages), 14, INK), wrap());
            }
        }
        if (count == 0) box.addView(text("没有识别到常用域代码。", 14, MUTED), wrap());
        new AlertDialog.Builder(this).setTitle("域代码").setView(box).setPositiveButton("关闭", null).show();
    }

    private void showCheck() {
        syncAll();
        DocxChecker.check(document, profile);
        LinearLayout box = column();
        box.setPadding(dp(18), 0, dp(18), 0);
        for (DocxDocument.FormatIssue issue : document.issues) {
            TextView row = text(issue.title + "\n" + issue.detail, 13, INK);
            row.setPadding(0, dp(8), 0, dp(8));
            box.addView(row, wrap());
        }
        new AlertDialog.Builder(this).setTitle("格式一致性检查").setView(box).setPositiveButton("关闭", null).show();
    }

    private void setBusy(boolean value) {
        busy = value;
        if (ribbon != null) ribbon.setEnabled(!value);
        if (activeEditor != null) activeEditor.setEnabled(!value);
        disableTree(reviewPanel, value);
    }

    private void disableTree(View view, boolean disabled) {
        if (view == null) return; view.setEnabled(!disabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) disableTree(group.getChildAt(i), disabled);
        }
    }

    @Override protected void onDestroy() { if (api != null) api.destroy(); super.onDestroy(); }

    private void exportPdfDialog() {
        if (document == null || busy) return;
        syncAll();
        LinearLayout box = column(); box.setPadding(dp(18), dp(8), dp(18), dp(8));
        android.widget.Spinner scope = new android.widget.Spinner(this);
        scope.setAdapter(new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"全文", "当前页", "选中内容"}));
        box.addView(scope, wrap());
        android.widget.CheckBox comments = new android.widget.CheckBox(this), revisions = new android.widget.CheckBox(this), bookmarks = new android.widget.CheckBox(this);
        comments.setText("包含批注"); revisions.setText("包含修订"); bookmarks.setText("生成书签"); bookmarks.setChecked(true);
        box.addView(comments, wrap()); box.addView(revisions, wrap()); box.addView(bookmarks, wrap());
        new AlertDialog.Builder(this).setTitle("导出 PDF").setView(box).setNegativeButton("取消", null)
                .setPositiveButton("导出", (dialog, which) -> {
                    PdfExportOptions options = new PdfExportOptions();
                    options.scope = PdfExportOptions.Scope.values()[scope.getSelectedItemPosition()];
                    options.currentPage = pageScroll.currentPage();
                    options.includeComments = comments.isChecked(); options.includeRevisions = revisions.isChecked(); options.bookmarks = bookmarks.isChecked();
                    if (options.scope == PdfExportOptions.Scope.SELECTION) {
                        if (activeEditor == null) return;
                        options.paragraphIndex = (Integer) activeEditor.getTag();
                        options.selectionStart = Math.max(0, Math.min(activeEditor.getSelectionStart(), activeEditor.getSelectionEnd()));
                        options.selectionEnd = Math.max(0, Math.max(activeEditor.getSelectionStart(), activeEditor.getSelectionEnd()));
                        try { options.validateSelection(paragraphFor(options.paragraphIndex)); }
                        catch (IllegalArgumentException error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_SHORT).show(); return; }
                    }
                    pendingPdf = options;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/pdf"); intent.putExtra(Intent.EXTRA_TITLE, fileName.replaceAll("(?i)\\.docx$", "") + ".pdf");
                    startActivityForResult(intent, REQUEST_PDF);
                }).show();
    }

    /** 自建库收两类东西：文档（.docx/.txt/.md/.pdf）与题录导出（.bib/.ris/.enl，见 RecordImport）。 */
    private void pickLibraryDocuments() {
        if (busy) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        // 系统给 .bib/.ris/.enl 报的往往是 octet-stream（有的 ROM 干脆报 bin），
        // 白名单里没有它，用户在文件选择器里就看不见自己刚导出的那份题录。
        pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "text/plain", "text/markdown", "application/octet-stream", "text/csv"});
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(pick, REQUEST_LIBRARY);
    }

    private void saveAs() {
        if (document == null || busy) return;
        syncAll();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        intent.putExtra(Intent.EXTRA_TITLE, fileName.replaceAll("(?i)\\.docx$", "") + "-编辑.docx");
        startActivityForResult(intent, REQUEST_SAVE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (api != null && api.activityResult(requestCode, resultCode, data)) return;
        if (requestCode == REQUEST_LIBRARY) {
            ArrayList<Uri> picked = new ArrayList<Uri>();
            if (data != null && data.getClipData() != null)
                for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    if (data.getClipData().getItemAt(i).getUri() != null) picked.add(data.getClipData().getItemAt(i).getUri());
            else if (data != null && data.getData() != null) picked.add(data.getData());
            if (resultCode == RESULT_OK && api != null) api.libraryImport(picked);
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_PDF && pendingPdf != null) {
            final Uri target = data.getData(); final PdfExportOptions options = pendingPdf;
            if (sourceUri.equals(target)) { pendingPdf = null; Toast.makeText(this, "输出路径无效", Toast.LENGTH_SHORT).show(); return; }
            pendingPdf = null;
            syncAll(); setBusy(true);
            new Thread(() -> {
                try (OutputStream output = getContentResolver().openOutputStream(target, "w")) {
                    PdfExporter.export(this, document, output, options);
                    runOnUiThread(() -> Toast.makeText(this, "PDF 已导出", Toast.LENGTH_SHORT).show());
                } catch (Exception error) {
                    runOnUiThread(() -> Toast.makeText(this, "PDF 导出失败", Toast.LENGTH_SHORT).show());
                } finally { runOnUiThread(() -> setBusy(false)); }
            }, "word-pdf-export").start();
        } else if (requestCode == REQUEST_SAVE) {
            final Uri target = data.getData();
            syncAll(); setBusy(true);
            new Thread(() -> {
                try {
                    DocxWriter.write(this, sourceUri, target, document);
                    runOnUiThread(() -> Toast.makeText(this, "文档已保存", Toast.LENGTH_LONG).show());
                } catch (final Throwable error) {
                    runOnUiThread(() -> Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show());
                } finally { runOnUiThread(() -> setBusy(false)); }
            }, "word-docx-save").start();
        } else if (requestCode == REQUEST_PICK) {
            onImagePicked(data.getData());
        }
    }

    /** Insert an image part into the paragraph being edited (or a new paragraph after it). */
    private void pickImage() {
        if (document == null) return;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("image/*");
        try {
            startActivityForResult(pick, REQUEST_PICK);
        } catch (Exception error) {
            Toast.makeText(this, "打不开图片选择器： " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void onImagePicked(Uri uri) {
        new Thread(() -> {
            try {
                // Bounded read: reject anything over 12 MB before decoding.
                byte[] bytes;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IllegalStateException("打不开图片");
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[16384];
                    int n, total = 0;
                    while ((n = in.read(buffer)) != -1) {
                        total += n;
                        if (total > 12 * 1024 * 1024) throw new IllegalStateException("图片超过 12 MB");
                        out.write(buffer, 0, n);
                    }
                    bytes = out.toByteArray();
                }
                // Downscale oversized bitmaps so the document stays readable.
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                String mime = getContentResolver().getType(uri);
                String ext = mime == null ? "png" : mime.contains("jpeg") ? "jpg" : mime.contains("gif") ? "gif" : "png";
                if (bounds.outWidth > 2048 || bounds.outHeight > 2048) {
                    BitmapFactory.Options full = new BitmapFactory.Options();
                    full.inJustDecodeBounds = false;
                    int sample = 1;
                    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2;
                    full.inSampleSize = sample;
                    Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, full);
                    if (bitmap == null) throw new IllegalStateException("图片解码失败");
                    java.io.ByteArrayOutputStream pngOut = new java.io.ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, pngOut);
                    bytes = pngOut.toByteArray();
                    bounds.outWidth = bitmap.getWidth();
                    bounds.outHeight = bitmap.getHeight();
                    ext = "png";
                    bitmap.recycle();
                }
                long stamp = System.currentTimeMillis();
                DocxDocument.EmbeddedImage image = new DocxDocument.EmbeddedImage(bytes, "插入图片 " + stamp);
                image.partName = "word/media/inserted-" + stamp + "." + ext;
                image.relationshipId = "rIdInserted" + stamp;
                image.isNew = true;
                // Width capped to the text area; 914400 EMU = 1 inch, 96 px per inch at document scale.
                int pxWidth = Math.max(1, bounds.outWidth);
                int pxHeight = Math.max(1, bounds.outHeight);
                DocxDocument.SectionSettings imageSection = document.section;
                if (!document.sections.isEmpty()) {
                    int sectionIndex = activeEditor != null && activeEditor.getTag() instanceof Integer
                            ? paragraphFor((Integer) activeEditor.getTag()).sectionIndex : document.section.startBlockIndex;
                    sectionIndex = Math.max(0, Math.min(sectionIndex, document.sections.size() - 1));
                    imageSection = document.sections.get(sectionIndex);
                }
                long maxEmu = Math.round(new A4Paginator(imageSection).contentWidth() * 9525);
                long emu = pxWidth * 9525L;
                if (emu > maxEmu) emu = maxEmu;
                image.widthEmu = (int) Math.max(1, emu);
                image.heightEmu = (int) Math.max(1, emu * pxHeight / pxWidth);
                attachImage(image);
                runOnUiThread(this::renderDocument);
            } catch (final Throwable error) {
                runOnUiThread(() -> Toast.makeText(this, "插入图片失败：" + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "word-docx-image").start();
    }

    /** Structural table editing for the table containing the open paragraph. */
    private void showTableDialog() {
        if (document == null) return;
        int blockIndex = activeEditor == null || activeEditor.getTag() == null ? -1 : (Integer) activeEditor.getTag();
        final TableOps.Location where = TableOps.locate(document, blockIndex);
        if (where == null) {
            Toast.makeText(this, "请先点击表格中的一段", Toast.LENGTH_SHORT).show();
            return;
        }
        final DocxDocument.TableBlock table = where.table;
        final int row = where.row, col = where.col;
        String[] operations = {"上方插入行", "下方插入行", "删除本行", "左侧插入列", "右侧插入列", "删除本列"};
        new AlertDialog.Builder(this).setTitle("表格结构")
                .setItems(operations, (d, which) -> {
                    boolean ok;
                    switch (which) {
                        case 0: ok = TableOps.insertRow(document, table, row, true); break;
                        case 1: ok = TableOps.insertRow(document, table, row, false); break;
                        case 2: ok = TableOps.deleteRow(document, table, row); break;
                        case 3: ok = TableOps.insertColumn(document, table, col, true); break;
                        case 4: ok = TableOps.insertColumn(document, table, col, false); break;
                        default: ok = TableOps.deleteColumn(document, table, col); break;
                    }
                    if (ok) renderDocument(); else Toast.makeText(this, "已是最小结构", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void jumpToPage() {
        if (document == null || pageScroll == null) return;
        int count = Math.max(1, totalPages);
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) labels[i] = "第 " + (i + 1) + " 页";
        new AlertDialog.Builder(this).setTitle("跳转到")
                .setItems(labels, (d, which) -> pageScroll.scrollToPage(which)).show();
    }

    /** Structural edits are intentionally not part of the paragraph undo stack. */
    private void attachImage(DocxDocument.EmbeddedImage image) {
        int blockIndex = activeEditor == null || activeEditor.getTag() == null ? -1 : (Integer) activeEditor.getTag();
        DocxDocument.ParagraphBlock target = paragraphFor(blockIndex);
        if (target != null) { target.images.add(image); return; }
        // No editor open: append a paragraph at the end with a fresh unique index.
        int next = 0;
        for (DocxDocument.Block block : document.blocks) next = Math.max(next, block.index + 1);
        DocxDocument.ParagraphBlock paragraph = new DocxDocument.ParagraphBlock();
        paragraph.index = next;
        document.blocks.add(paragraph);
        document.paragraphs.add(paragraph);
        paragraph.images.add(image);
    }

    private void syncAll() {
        for (Map.Entry<Integer, EditText> entry : editors.entrySet()) {
            DocxDocument.ParagraphBlock paragraph = paragraphFor(entry.getKey());
            if (paragraph != null) syncParagraph(paragraph, entry.getValue());
        }
    }

    private void syncParagraph(DocxDocument.ParagraphBlock paragraph, EditText editor) {
        String value = editor.getText().toString();
        paragraph.text = value;
        Spannable spannable = editor.getText();
        paragraph.runs.clear();
        if (value.length() == 0) return;
        int cursor = 0;
        while (cursor < value.length()) {
            int end = cursor + 1;
            DocxDocument.RunStyle style = styleAt(spannable, cursor);
            while (end < value.length() && sameStyle(style, styleAt(spannable, end))) end++;
            paragraph.runs.add(new DocxDocument.Run(value.substring(cursor, end), style));
            cursor = end;
        }
    }

    private DocxDocument.RunStyle styleAt(Spannable text, int offset) {
        return DocxTextLayout.styleAt(text, offset, editorPointScale());
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private boolean sameStyle(DocxDocument.RunStyle a, DocxDocument.RunStyle b) {
        return a.bold == b.bold && a.italic == b.italic && a.underline == b.underline
                && a.strike == b.strike && a.superscript == b.superscript && a.subscript == b.subscript
                && a.highlight == b.highlight && a.colorSet == b.colorSet
                && a.fontSizeHalfPoints == b.fontSizeHalfPoints && a.color == b.color
                && a.complexScriptSizeHalfPoints == b.complexScriptSizeHalfPoints
                && a.positionSet == b.positionSet && a.positionHalfPoints == b.positionHalfPoints
                && a.superscriptSet == b.superscriptSet && a.subscriptSet == b.subscriptSet
                && same(a.fontFamily, b.fontFamily)
                && same(a.asciiFontFamily, b.asciiFontFamily)
                && same(a.highAnsiFontFamily, b.highAnsiFontFamily)
                && same(a.complexScriptFontFamily, b.complexScriptFontFamily)
                && same(a.eastAsiaFontFamily, b.eastAsiaFontFamily);
    }

    private int[] selectionOrParagraph(EditText editor) {
        int start = Math.max(0, editor.getSelectionStart());
        int end = Math.max(0, editor.getSelectionEnd());
        if (start == end) { start = 0; end = editor.length(); }
        return new int[]{Math.min(start, end), Math.max(start, end)};
    }

    private EditText currentEditor() {
        if (activeEditor != null) return activeEditor;

        return null;
    }

    private DocxDocument.ParagraphBlock paragraphFor(Integer index) {
        if (index == null || document == null) return null;
        for (DocxDocument.ParagraphBlock paragraph : document.paragraphs) if (paragraph.index == index) return paragraph;
        return null;
    }

    private boolean hasIssue(int index) {
        if (document == null) return false;
        for (DocxDocument.FormatIssue issue : document.issues) if (issue.blockIndex == index) return true;
        return false;
    }

    private int gravityFor(int alignment) {
        if (alignment == 1) return Gravity.CENTER_HORIZONTAL;
        if (alignment == 2) return Gravity.RIGHT;
        if (alignment == 3) return Gravity.LEFT;
        return Gravity.LEFT;
    }

    private void addTool(LinearLayout tools, String label, View.OnClickListener listener) {
        Button button = button(label, 13);
        if (label.equals("B")) button.setTypeface(Typeface.DEFAULT_BOLD);
        else if (label.equals("I")) button.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        else if (label.equals("U")) button.setPaintFlags(button.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        button.setOnClickListener(listener);
        int width = label.length() > 2 ? 58 : (label.length() > 1 ? 48 : 36);
        tools.addView(button, new LinearLayout.LayoutParams(dp(width), dp(38)));
    }

    // ─── Undo / Redo ─────────────────────────────────────────────────────
    private UndoEntry snapshot(int blockIndex) {
        DocxDocument.ParagraphBlock p = paragraphFor(blockIndex);
        if (p == null) return null;
        EditText editor = editors.get(blockIndex);
        if (editor != null) syncParagraph(p, editor);
        UndoEntry entry = new UndoEntry();
        entry.blockIndex = blockIndex; entry.text = p.text; entry.format = p.format.copy();
        entry.runs = new ArrayList<DocxDocument.Run>();
        for (DocxDocument.Run run : p.runs) entry.runs.add(new DocxDocument.Run(run.text, run.style.copy()));
        entry.revisions = new ArrayList<DocxDocument.Revision>();
        for (DocxDocument.Revision revision : document.revisions) if (revision.paragraphIndex == blockIndex) entry.revisions.add(ReviewCopies.copyRevision(revision));
        entry.comments = new ArrayList<DocxDocument.Comment>();
        for (DocxDocument.Comment comment : document.comments) if (comment.paragraphIndex == blockIndex) entry.comments.add(ReviewCopies.copyComment(comment));
        entry.fields = new ArrayList<DocxDocument.FieldCode>();
        for (DocxDocument.FieldCode field : p.fields) {
            DocxDocument.FieldCode clone = new DocxDocument.FieldCode(field.instruction, field.cachedResult, field.simple);
            clone.start = field.start; clone.end = field.end; entry.fields.add(clone);
        }
        entry.hyperlinks = new ArrayList<DocxDocument.Hyperlink>();
        for (DocxDocument.Hyperlink link : p.hyperlinks) {
            DocxDocument.Hyperlink clone = new DocxDocument.Hyperlink(); clone.start = link.start; clone.end = link.end;
            clone.target = link.target; clone.relationshipId = link.relationshipId; clone.anchor = link.anchor; entry.hyperlinks.add(clone);
        }
        return entry;
    }

    private void pushUndo(int blockIndex) {
        UndoEntry entry = snapshot(blockIndex);
        if (entry == null) return;
        undoStack.add(entry);
        if (undoStack.size() > 50) undoStack.remove(0);
        redoStack.clear();
    }

    private void restore(UndoEntry entry) {
        DocxDocument.ParagraphBlock p = paragraphFor(entry.blockIndex);
        if (p == null) return;
        p.text = entry.text; p.format.set(entry.format); p.runs.clear();
        for (DocxDocument.Run run : entry.runs) p.runs.add(new DocxDocument.Run(run.text, run.style.copy()));
        for (int i = document.revisions.size() - 1; i >= 0; i--)
            if (document.revisions.get(i).paragraphIndex == entry.blockIndex) document.revisions.remove(i);
        for (DocxDocument.Revision revision : entry.revisions) document.revisions.add(ReviewCopies.copyRevision(revision));
        for (int i = document.comments.size() - 1; i >= 0; i--)
            if (document.comments.get(i).paragraphIndex == entry.blockIndex) document.comments.remove(i);
        for (DocxDocument.Comment comment : entry.comments) document.comments.add(ReviewCopies.copyComment(comment));
        p.fields.clear(); p.fields.addAll(entry.fields); p.hyperlinks.clear(); p.hyperlinks.addAll(entry.hyperlinks); document.hasTrackedChanges = !document.revisions.isEmpty();
        p.textEdited = true;
        editors.clear(); activeEditor = null;
        renderDocument();
        openParagraphEditor(p.index, p.text.length());
    }

    private void undo() {
        if (undoStack.isEmpty()) { return; }
        UndoEntry entry = undoStack.remove(undoStack.size() - 1);
        UndoEntry current = snapshot(entry.blockIndex);
        if (current != null) { redoStack.add(current); restore(entry); }
    }

    private void redo() {
        if (redoStack.isEmpty()) { return; }
        UndoEntry entry = redoStack.remove(redoStack.size() - 1);
        UndoEntry current = snapshot(entry.blockIndex);
        if (current != null) { undoStack.add(current); restore(entry); }
    }

    private Button button(String label, int size) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(size);
        button.setTextColor(JADE);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(0, 0, 0, 0);
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        if (value.resourceId != 0) button.setBackground(getDrawable(value.resourceId));
        return button;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-1, -2); }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private int statusBarInset() {
        int resource = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int height = resource > 0 ? getResources().getDimensionPixelSize(resource) : 0;
        return Math.max(height, dp(24));
    }

    private void styleSystemBars() {
        boolean dark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        getWindow().setStatusBarColor(dark ? 0xFF172D47 : 0xFF244D77);
        getWindow().setNavigationBarColor(dark ? 0xFF252525 : 0xFFF7F7F7);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
}

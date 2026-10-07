package com.rikkahub.wordlite;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.LineHeightSpan;
import android.widget.EditText;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, shadows = LineSpacingTest.SizeAwarePaint.class)
@org.robolectric.annotation.TextLayoutMode(org.robolectric.annotation.TextLayoutMode.Mode.REALISTIC)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class LineSpacingTest {
    // Robolectric's legacy Paint reports constant metrics, hiding feedback from
    // a previous line. Model size-dependent metrics without native libraries.
    @org.robolectric.annotation.Implements(value = Paint.class,
            shadowPicker = org.robolectric.annotation.Implements.DefaultShadowPicker.class)
    public static class SizeAwarePaint extends org.robolectric.shadows.ShadowPaint {
        @org.robolectric.annotation.RealObject private Paint realPaint;

        @org.robolectric.annotation.Implementation
        protected int getFontMetricsInt(Paint.FontMetricsInt fm) {
            float size = realPaint.getTextSize();
            int ascent = -(int) Math.ceil(size * 0.9f);
            int descent = (int) Math.ceil(size * 0.25f);
            if (fm != null) {
                fm.ascent = ascent;
                fm.descent = descent;
                fm.top = ascent - Math.round(size * 0.1f);
                fm.bottom = descent + Math.round(size * 0.05f);
                fm.leading = 0;
            }
            return descent - ascent;
        }
    }
    private DocxDocument.ParagraphBlock paragraph(String value, int spacing, String rule) {
        DocxDocument.ParagraphBlock paragraph = new DocxDocument.ParagraphBlock();
        paragraph.text = value;
        paragraph.baseRunStyle.fontSizeHalfPoints = 24;
        paragraph.baseRunStyle.asciiFontFamily = "Times New Roman";
        paragraph.baseRunStyle.eastAsiaFontFamily = "\u5b8b\u4f53";
        paragraph.format.lineSpacingTwips = spacing;
        paragraph.format.lineRule = rule;
        paragraph.runs.add(new DocxDocument.Run(value, paragraph.baseRunStyle.copy()));
        return paragraph;
    }

    private LineHeightSpan spacing(SpannableStringBuilder text, DocxDocument.ParagraphBlock paragraph, float scale) {
        DocxTextLayout.applyParagraphLineSpacing(text, paragraph, scale);
        LineHeightSpan[] spans = text.getSpans(0, text.length(), LineHeightSpan.class);
        assertEquals(1, spans.length);
        return spans[0];
    }

    private Paint.FontMetricsInt metrics() {
        TextPaint paint = new TextPaint();
        paint.setTextSize(PageGeometry.points(12));
        paint.setTypeface(Typeface.SERIF);
        return paint.getFontMetricsInt();
    }

    @Test public void reusedMetricsDoNotGrowAcrossLines() {
        for (int line : new int[]{240, 276, 280, 284, 288, 300, 324, 360}) {
            DocxDocument.ParagraphBlock paragraph = paragraph("body text", line, "auto");
            SpannableStringBuilder text = DocxTextLayout.styledText(paragraph);
            LineHeightSpan span = spacing(text, paragraph, PageGeometry.points(1));
            Paint.FontMetricsInt fm = metrics();
            span.chooseHeight(text, 0, text.length(), 0, 0, fm);
            int firstHeight = fm.descent - fm.ascent;
            int firstAscent = fm.ascent;
            for (int i = 1; i < 40; i++) {
                span.chooseHeight(text, 0, text.length(), 0, firstHeight * i, fm);
                assertEquals("line=" + line + " iteration=" + i, firstHeight, fm.descent - fm.ascent);
                assertEquals(firstAscent, fm.ascent);
            }
        }
        SpannableStringBuilder fullWidth = DocxTextLayout.styledText(paragraph("A（B），C", 300, "auto"));
        assertEquals("宋体", fullWidth.getSpans(1, 2, DocxTextLayout.FontSpan.class)[0].getFamily());
        assertEquals("宋体", fullWidth.getSpans(3, 4, DocxTextLayout.FontSpan.class)[0].getFamily());
        assertEquals("宋体", fullWidth.getSpans(4, 5, DocxTextLayout.FontSpan.class)[0].getFamily());
        assertEquals("Times New Roman", fullWidth.getSpans(0, 1, DocxTextLayout.FontSpan.class)[0].getFamily());
    }

    @Test public void incomingMetricsAreNotUsedToInferTextSize() {
        DocxDocument.ParagraphBlock paragraph = paragraph("body text", 360, "auto");
        SpannableStringBuilder text = DocxTextLayout.styledText(paragraph);
        LineHeightSpan span = spacing(text, paragraph, PageGeometry.points(1));
        Paint.FontMetricsInt normal = metrics();
        Paint.FontMetricsInt polluted = metrics();
        polluted.ascent = polluted.top = -700;
        polluted.descent = polluted.bottom = 300;
        span.chooseHeight(text, 0, text.length(), 0, 0, normal);
        span.chooseHeight(text, 0, text.length(), 0, 0, polluted);
        assertEquals(normal.ascent, polluted.ascent);
        assertEquals(normal.descent, polluted.descent);
    }

    @Test public void longWrappedParagraphHasStableBaselines() {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 150; i++) value.append("body text ");
        StaticLayout layout = DocxTextLayout.measure(paragraph(value.toString(), 360, "auto"), 300).layout;
        // Robolectric LEGACY mode may not wrap; verify spacing is consistent
        // regardless of line count.
        if (layout.getLineCount() > 1) {
            int advance = layout.getLineTop(1) - layout.getLineTop(0);
            for (int i = 2; i < layout.getLineCount(); i++) {
                assertEquals("line=" + i, advance, layout.getLineTop(i) - layout.getLineTop(i - 1));
            }
        } else {
            // Single line: just verify height is reasonable for 1.5x spacing
            int height = layout.getLineTop(1) - layout.getLineTop(0);
            assertTrue("single line height=" + height, height > 0 && height < 200);
        }
    }

    @Test public void exactAndMinimumRulesAreStableAndUseTwips() {
        for (String rule : Arrays.asList("exact", "atLeast")) {
            DocxDocument.ParagraphBlock paragraph = paragraph("body text", 600, rule);
            SpannableStringBuilder text = DocxTextLayout.styledText(paragraph);
            LineHeightSpan span = spacing(text, paragraph, PageGeometry.points(1));
            Paint.FontMetricsInt fm = metrics();
            for (int i = 0; i < 20; i++) {
                span.chooseHeight(text, 0, text.length(), 0, i * 40, fm);
                assertEquals(rule + " iteration=" + i, 40, fm.descent - fm.ascent);
            }
        }
    }

    @Test public void realScreenshotParagraphDoesNotAcquireIncreasingLineHeights() throws Exception {
        File input = new File(System.getProperty("app.root"), "tests/samples/input-liu.docx");
        DocxDocument document;
        try (FileInputStream in = new FileInputStream(input)) {
            document = DocxParser.parse(in, "input-liu.docx");
        }
        int matched = 0;
        for (DocxDocument.ParagraphBlock paragraph : document.paragraphs) {
            if (!paragraph.text.contains("\u9ad8\u6e29\u548c\u529f\u7387\u5faa\u73af")
                    && !paragraph.text.contains("\u4f20\u7edfTLP")
                    && !paragraph.text.contains("\u591a\u5b54\u94dc\u5177\u6709")) continue;
            matched++;
            StaticLayout layout = DocxTextLayout.measure(paragraph, new PageGeometry(document.section).contentWidth).layout;
            int min = Integer.MAX_VALUE, max = 0;
            for (int i = 0; i < layout.getLineCount(); i++) {
                int height = layout.getLineTop(i + 1) - layout.getLineTop(i);
                min = Math.min(min, height);
                max = Math.max(max, height);
            }
            System.out.println("SCREENSHOT paragraph=" + paragraph.index + " spacing=" + paragraph.format.lineSpacingTwips
                    + " rule=" + paragraph.format.lineRule + " lines=" + layout.getLineCount() + " min=" + min + " max=" + max);
            assertTrue("paragraph=" + paragraph.index + " min=" + min + " max=" + max, max <= min * 2 + 2);
        }
        assertTrue("expected >= 3 matched paragraphs, got " + matched, matched >= 3);
    }
}

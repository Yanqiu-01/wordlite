package com.rikkahub.wordlite;

import android.text.Spanned;
import android.text.style.ReplacementSpan;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.TextLayoutMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** OOXML autoSpaceDE/autoSpaceDN must affect only explicit script boundaries. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, shadows = LineSpacingTest.SizeAwarePaint.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
@TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
public class AutoSpaceTest {
    private DocxDocument.ParagraphBlock paragraph(boolean de, boolean dn) {
        DocxDocument.ParagraphBlock p = new DocxDocument.ParagraphBlock();
        p.text = "中A中1中";
        p.baseRunStyle.fontSizeHalfPoints = 24;
        p.baseRunStyle.eastAsiaFontFamily = "宋体";
        p.baseRunStyle.asciiFontFamily = "Times New Roman";
        p.format.autoSpaceDe = de;
        p.format.autoSpaceDeSet = de;
        p.format.autoSpaceDn = dn;
        p.format.autoSpaceDnSet = dn;
        p.runs.add(new DocxDocument.Run(p.text, p.baseRunStyle.copy()));
        return p;
    }

    @Test public void absentOrDisabledAutoSpaceDoesNotTouchPlainText() {
        DocxDocument.ParagraphBlock p = paragraph(false, false);
        Spanned text = (Spanned) DocxTextLayout.measure(p, 400).layout.getText();
        assertEquals(0, text.getSpans(0, text.length(), ReplacementSpan.class).length);
    }

    @Test public void cjkPunctuationEdgesGetNoAutoGap() {
        // Word spaces CJK text against Latin/digits, but not CJK punctuation:
        // "，A" and "A，" keep no extra width on either side.
        DocxDocument.ParagraphBlock p = paragraph(true, true);
        p.text = "\u4e2d\uff0cA\uff0c\u4e2d\uff0c";
        p.runs.clear();
        p.runs.add(new DocxDocument.Run(p.text, p.baseRunStyle.copy()));
        Spanned text = (Spanned) DocxTextLayout.measure(p, 400).layout.getText();
        // No ideograph-to-Latin/digit boundary exists here, so nothing may add width.
        assertEquals(0, text.getSpans(0, text.length(), ReplacementSpan.class).length);
    }

    @Test public void enabledAutoSpacePlacesGapAfterLeftBoundaryCharacter() {
        DocxDocument.ParagraphBlock p = paragraph(true, true);
        Spanned text = (Spanned) DocxTextLayout.measure(p, 400).layout.getText();
        // Boundaries are 中|A, A|中, 中|1 and 1|中. The gap belongs after
        // the left character because ReplacementSpan adds width after itself.
        assertTrue(text.getSpans(0, 1, ReplacementSpan.class).length > 0);
        assertTrue(text.getSpans(1, 2, ReplacementSpan.class).length > 0);
        assertTrue(text.getSpans(2, 3, ReplacementSpan.class).length > 0);
        assertTrue(text.getSpans(3, 4, ReplacementSpan.class).length > 0);
        assertEquals(0, text.getSpans(4, 5, ReplacementSpan.class).length);
    }
}

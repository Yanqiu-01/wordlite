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

    @Test public void declaredOffAutoSpaceDoesNotTouchPlainText() {
        // w:autoSpaceDE/@w:val="0" written out is the only thing that removes the gap.
        DocxDocument.ParagraphBlock p = paragraph(false, false);
        Spanned text = (Spanned) DocxTextLayout.measure(p, 400).layout.getText();
        assertEquals(0, text.getSpans(0, text.length(), ReplacementSpan.class).length);
    }

    @Test public void silentAutoSpaceKeepsTheWordDefaultGap() {
        // OOXML leaves the pair on unless the file declares it off. 268 of the 373 paragraphs
        // in tests/samples/input-liu.docx never mention it, and Word still spaces CJK against
        // Latin there, so a paragraph that says nothing must draw the same gaps as one that
        // declares the pair on.
        DocxDocument.ParagraphBlock p = paragraph(true, true);
        p.format.autoSpaceDeSet = false;
        p.format.autoSpaceDnSet = false;
        Spanned text = (Spanned) DocxTextLayout.measure(p, 400).layout.getText();
        assertEquals("three seams, one per CJK character that touches Latin or a digit", 3,
                text.getSpans(0, text.length(), DocxTextLayout.AutoGap.class).length);
        assertTrue(text.getSpans(0, 1, ReplacementSpan.class).length > 0);   // 中 | A
        assertTrue(text.getSpans(2, 3, ReplacementSpan.class).length > 0);   // A | 中 and 中 | 1, one span
        assertTrue(text.getSpans(4, 5, ReplacementSpan.class).length > 0);   // 1 | 中
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

    /**
     * Where the quarter em sits. It used to ride the character on the left of the seam, which put a
     * ReplacementSpan edge inside the Latin run; StaticLayout reads a span edge as a break opportunity
     * and cut the word in half -- "...SB/C" | "u 夹层结构" on page 8 of the phone, 10 lines of the thesis
     * like that (tools/midword-audit.py on
     * artifacts/agent-layout-verify/revert-linear/new/lines-all.tsv: token_cut=10, and 1 after the move).
     * Word cuts 0 of its 238 real breaks inside a token (tools/break-class-truth.py) and the same phone
     * breaks an untouched token before it (tools/breakiterator-probe.ps1 -Probe ScriptBreakProbe,
     * variant A vs B/C). So every seam rides the Chinese character, and the Latin and digit sides stay
     * span-free.
     */
    @Test public void everySeamRidesTheChineseCharacterOfTheBoundary() {
        DocxDocument.ParagraphBlock p = paragraph(true, true);
        Spanned text = (Spanned) DocxTextLayout.measure(p, 400).layout.getText();
        // 中A中1中: seams 中|A, A|中, 中|1, 1|中 ride 中(0), 中(2), 中(2), 中(4).
        assertTrue(text.getSpans(0, 1, DocxTextLayout.AutoGap.class).length > 0);
        assertTrue(text.getSpans(2, 3, DocxTextLayout.AutoGap.class).length > 0);
        assertTrue(text.getSpans(4, 5, DocxTextLayout.AutoGap.class).length > 0);
        assertEquals("the A carries no seam of its own", 0,
                text.getSpans(1, 2, ReplacementSpan.class).length);
        assertEquals("neither does the 1", 0,
                text.getSpans(3, 4, ReplacementSpan.class).length);
    }
}

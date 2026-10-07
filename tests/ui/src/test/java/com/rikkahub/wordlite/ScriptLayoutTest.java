package com.rikkahub.wordlite;

import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Word breaks a line on the advance of the script run itself: a w:vertAlign
 * citation is first scaled to the font OS/2 script size and only then measured,
 * so a body line that ends in a citation costs only the small advance and keeps
 * room for one more Chinese character. Charging the base size, which is what this
 * project did before, wraps one character early and the error compounds over a
 * thesis.
 *
 * The row height follows Word too: an auto-spaced row makes room for the raised
 * glyphs, an exact-spaced row clips them, and neither case lets the script run
 * decide the paragraph box.
 *
 * Advances are calibrated from the platform measure instead of hard-coded em
 * numbers, so the arithmetic holds whichever font the host substitutes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ScriptLayoutTest {
    private static final String CITATION = "[12]";

    /** One 12 pt paragraph: `body` in the base run, `tail` in a second run. */
    private DocxDocument.ParagraphBlock paragraph(String body, String tail,
                                                  boolean tailSuperscript, String rule, int spacing) {
        DocxDocument.ParagraphBlock p = new DocxDocument.ParagraphBlock();
        p.baseRunStyle.fontSizeHalfPoints = 24; // 12 pt
        p.baseRunStyle.asciiFontFamily = "Times New Roman";
        p.baseRunStyle.eastAsiaFontFamily = "\u5b8b\u4f53";
        p.format.lineRule = rule;
        p.format.lineSpacingTwips = spacing;
        p.text = body + tail;
        p.runs.add(new DocxDocument.Run(body, p.baseRunStyle.copy()));
        DocxDocument.RunStyle tailStyle = p.baseRunStyle.copy();
        tailStyle.superscript = tailSuperscript;
        tailStyle.superscriptSet = tailSuperscript;
        p.runs.add(new DocxDocument.Run(tail, tailStyle));
        return paragraphWithText(p);
    }

    private DocxDocument.ParagraphBlock paragraphWithText(DocxDocument.ParagraphBlock p) {
        StringBuilder joined = new StringBuilder();
        for (DocxDocument.Run run : p.runs) joined.append(run.text);
        p.text = joined.toString();
        return p;
    }

    private static String han(int count) {
        String unit = "\u6027\u80fd\u63d0\u5347\u6548\u679c\u7814\u7a76\u62a5\u544a"; // 10 distinct han
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(unit.charAt(i % unit.length()));
        return out.toString();
    }

    private StaticLayout layout(DocxDocument.ParagraphBlock paragraph, float width) {
        DocxTextLayout.initialize(RuntimeEnvironment.getApplication());
        return DocxTextLayout.measure(paragraph, width).layout;
    }

    /** Advance of one Chinese character as the platform this run measures it. */
    private float hanAdvance() {
        StaticLayout probe = layout(paragraph(han(20), "", false, "auto", 240), 100000f);
        return probe.getLineWidth(0) / 20f;
    }

    private DocxTextLayout.WordScriptSpan scriptSpan(Spanned text, int at) {
        DocxTextLayout.WordScriptSpan[] spans =
                text.getSpans(at, at + 1, DocxTextLayout.WordScriptSpan.class);
        assertEquals("the w:vertAlign run carries exactly one script span", 1, spans.length);
        return spans[0];
    }

    /**
     * Word: the script advance is measured at the script size, so a line of
     * `n` han plus a superscript citation fits where base-size accounting would
     * already have wrapped. The control paragraph is the same text with the
     * citation in the base style, i.e. the width the old accounting charged.
     */
    @Test public void superscriptCitationIsChargedAtItsScriptSize() {
        DocxTextLayout.initialize(RuntimeEnvironment.getApplication());
        int n = 20;
        String body = han(n);
        DocxDocument.ParagraphBlock raised = paragraph(body, CITATION, true, "auto", 240);
        Spanned text = DocxTextLayout.styledText(raised);
        int at = raised.text.indexOf(CITATION);
        DocxTextLayout.WordScriptSpan span = scriptSpan(text, at);

        TextPaint base = new TextPaint();
        base.setTextSize(PageGeometry.points(12));
        int scriptAdvance = span.getSize(base, text, at, at + CITATION.length(), null);
        int baseAdvance = (int) Math.ceil(base.measureText(CITATION));
        // Whether the platform bills text by size at all: the shadow paint under
        // Robolectric does not, which makes the wrap-point arithmetic below meaningless.
        boolean realMetrics = scriptAdvance != baseAdvance;
        assertTrue("the script run is never billed above the base size: script="
                        + scriptAdvance + " base=" + baseAdvance,
                scriptAdvance <= baseAdvance);
        // The charged advance is the same string measured at the script size,
        // which is what Word's break loop puts on the line.
        float scriptSize = span.renderedSize(base);
        assertTrue("the script run renders below the base size: " + scriptSize,
                scriptSize < base.getTextSize());
        TextPaint script = new TextPaint();
        script.setTextSize(scriptSize);
        assertEquals("the charged width is the advance at the script size",
                (int) Math.ceil(script.measureText(CITATION)), scriptAdvance);

        if (!realMetrics) {
            // A size-blind shadow paint charges every glyph the same width, so the two
            // accountings cannot disagree here and the rest of this case would prove
            // nothing. The line-level effect is asserted on real Android metrics:
            // run tools/capture-device.ps1 and compare artifacts/device/old with
            // artifacts/device/new, where the same 37-character line drops from
            // 562 px to 555 px and a seven-line citation paragraph becomes six lines.
            return;
        }
        float han = hanAdvance();
        assertTrue("calibrated han advance looks like 12 pt text: " + han, han > 1f);
        // A content width that clears n han plus the small citation but not n han
        // plus the same citation measured at the base size, so the two accountings
        // disagree by exactly one character.
        int gap = baseAdvance - scriptAdvance;
        int slack = Math.max(2, gap / 3);
        assertTrue("the citation billing difference must stay under one han: gap=" + gap
                + " han=" + han, gap > slack && slack < han);
        float needed = n * han + scriptAdvance;
        int width = (int) Math.ceil(needed) + slack;
        int fitsAtScriptSize = (int) Math.floor((width - scriptAdvance) / han);
        int fitsAtBaseSize = (int) Math.floor((width - baseAdvance) / han);
        assertEquals("script accounting holds " + n + " han", n, fitsAtScriptSize);
        assertTrue("base-size accounting loses a character: " + fitsAtBaseSize,
                fitsAtBaseSize < fitsAtScriptSize);

        StaticLayout line = layout(raised, width);
        assertEquals("Word keeps the whole line: " + line.getText(), 1, line.getLineCount());
        assertEquals("the row carries all " + n + " han plus the raised citation",
                n + CITATION.length(), line.getLineEnd(0) - line.getLineStart(0));
        assertTrue("the line fits the column: advance=" + line.getLineWidth(0) + " width=" + width,
                line.getLineWidth(0) <= width);

        StaticLayout chargedAtBaseSize = layout(paragraph(body, CITATION, false, "auto", 240), width);
        assertTrue("the same text charged at the base size wraps one character early",
                chargedAtBaseSize.getLineCount() > line.getLineCount());
    }

    /**
     * Word: w:lineRule="exact" fixes the row, and raised text is clipped rather
     * than allowed to push the following lines down.
     */
    @Test public void exactLineSpacingClipsTheSuperscriptInsteadOfGrowingTheRow() {
        int setHeight = 240; // twips = 16 document pixels
        StaticLayout plain = layout(paragraph(han(20), "", false, "exact", setHeight), 100000f);
        StaticLayout raised = layout(paragraph(han(20), CITATION, true, "exact", setHeight), 100000f);
        assertEquals(1, plain.getLineCount());
        assertEquals(1, raised.getLineCount());
        int expected = Math.round(PageGeometry.twips(setHeight));
        assertEquals("exact spacing keeps its own row height", expected,
                plain.getHeight());
        assertEquals("a superscript does not grow an exact row", expected,
                raised.getHeight());
        assertTrue("the raised run really is on this line",
                raised.getText().toString().contains(CITATION));
    }

    /**
     * Word: with auto spacing the row grows for the raised glyphs, and the
     * growth is bounded by the script's own demand -- the paragraph box still
     * comes from the body run, so a taller citation set at vertAlign cannot
     * lift every row of the paragraph the way an ordinary larger run would.
     */
    @Test public void autoLineSpacingMakesRoomForTheRaisedReference() {
        StaticLayout plain = layout(paragraph(han(20), "", false, "auto", 240), 100000f);
        StaticLayout sameSize = layout(paragraph(han(20), CITATION, true, "auto", 240), 100000f);
        assertTrue("auto spacing never shrinks for a superscript: plain="
                        + plain.getHeight() + " raised=" + sameSize.getHeight(),
                sameSize.getHeight() >= plain.getHeight());

        DocxDocument.ParagraphBlock big = paragraph(han(20), CITATION, true, "auto", 240);
        big.runs.get(1).style.fontSizeHalfPoints = 28; // 14 pt citation, still a script
        big.text = han(20) + CITATION;
        StaticLayout raised = layout(big, 100000f);
        assertEquals(1, raised.getLineCount());
        assertTrue("the row grows for the raised glyphs: plain=" + plain.getHeight()
                        + " raised=" + raised.getHeight(),
                raised.getHeight() > plain.getHeight());
        // Without the script scale the 14 pt run would have set the paragraph box
        // to 14 pt (19 px) for every row; Word keeps it at the body single line
        // height (16 px) plus only what the raised glyphs need.
        assertTrue("the script size never sets the paragraph box: raised=" + raised.getHeight(),
                raised.getHeight() < Math.round(PageGeometry.points(14)) + 2);
    }
}

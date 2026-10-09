package com.rikkahub.wordlite;

import android.text.Spanned;
import android.text.StaticLayout;
import android.text.style.ReplacementSpan;
import java.io.File;
import java.io.FileInputStream;
import org.junit.Ignore;
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
 * Word-anchored break rules for the real thesis body. The reference
 * screenshots measure the Kim paragraph as 8 lines whose last line starts at
 * "300 ℃下…" (ink edge 434.2 doc px from the left margin, advance 444.5) and
 * the 在连接过程中 paragraph as 11 lines ending "…提供了参考。" (advance 522).
 * Those exact break positions need a real line breaker, which Robolectric
 * cannot provide on this host (native runtime is unsupported on Linux
 * aarch64), so they are kept as documented @Ignore targets below, while the
 * span-level rules they depend on are asserted directly.
 *
 * Who can actually run this file: nothing on this machine, and nothing in the release path.
 * tools/test-host.ps1 compiles tests/*.java non-recursively (so never this directory), tools/test.sh
 * runs only the classes tools/build.sh produced, the repo has no CI config, and tools/archive-release.sh
 * merely tars tests/ui/src into the source archive. The single path that reaches these tests is
 * sh tools/test-native.sh com.rikkahub.wordlite.WordBreakLayoutTest, which needs an x86_64 JRE plus a
 * gradle distribution on the ARM build host and exits 2 (Native JRE unavailable; this test is skipped)
 * without them, and whose default class is PdfNativeTest -- so nobody runs it by accident either.
 * Read that as: the AutoGap swap in kimPunctuationEdgesCarryNoAutoGap (the Cu3Sn word sits under ONE
 * ScriptTokenSpan now, which is the whole point of that container) has never been executed anywhere,
 * 本机未跑 -- it is an unverified edit, not a passing test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class WordBreakLayoutTest {

    @Ignore("exact break positions need Robolectric native graphics; unsupported on Linux aarch64 host")
    @Test public void kimParagraphBreaksExactlyLikeWord() throws Exception {
        StaticLayout layout = layout(kim());
        assertEquals(8, layout.getLineCount());
        int lastStart = layout.getLineStart(layout.getLineCount() - 1);
        assertEquals("300 \u2103\u4e0b\u5ef6\u957f\u4fdd\u6e29\u65f6\u95f4\u9700\u8981\u517c\u987e\u754c\u9762\u53cd\u5e94\u7a0b\u5ea6\u548c\u53cd\u5e94\u6536\u7f29\u7f3a\u9677\u3002",
                kim().text.substring(lastStart));
    }

    @Ignore("exact break positions need Robolectric native graphics; unsupported on Linux aarch64 host")
    @Test public void zaiParagraphKeepsWordBreaks() throws Exception {
        StaticLayout layout = layout(zai());
        assertEquals(11, layout.getLineCount());
        int lastStart = layout.getLineStart(layout.getLineCount() - 1);
        assertEquals("\u529b\u63a7\u5236\u76f8\u4e92\u5173\u8054\uff0c\u4e3a\u540e\u7eed\u8bbe\u8ba1\u591a\u5b54Cu\u4e2d\u95f4\u5c42\u53ca\u786e\u5b9a\u8fde\u63a5\u53c2\u6570\u63d0\u4f9b\u4e86\u53c2\u8003\u3002",
                zai().text.substring(lastStart));
    }

    /** "Sn，" and "、0.1" are punctuation edges: Word adds no autoSpace there. */
    @Test public void kimPunctuationEdgesCarryNoAutoGap() throws Exception {
        Spanned text = (Spanned) layout(kim()).getText();
        int comma = kim().text.indexOf("Sn\uff0c\u4f5c\u8005") + 1; // the ， after Cu₃Sn
        // The Cu₃Sn word now sits under ONE ScriptTokenSpan on purpose -- a span per script run
        // is what let the breaker cut the word in half -- so "no ReplacementSpan here" is no longer
        // the thing under test. What this line is about is the auto-space gap, so ask for that.
        assertEquals(0, text.getSpans(comma - 1, comma, DocxTextLayout.AutoGap.class).length);
        int dunhao = kim().text.indexOf("\u30010.1"); // 、 before 0.1
        assertTrue(dunhao > 0);
        assertEquals(0, text.getSpans(dunhao, dunhao + 1, ReplacementSpan.class).length);
        // Positive control: the ideograph boundary "a|条" keeps its gap.
        int mpaA = kim().text.indexOf("MPa\u6761\u4ef6") + 2;
        assertTrue(text.getSpans(mpaA, mpaA + 1, ReplacementSpan.class).length > 0);
    }

    /** U+2086 renders as its real glyph at full size, not a scaled plain digit. */
    @Test public void unicodeSubscriptUsesFullSizeRealGlyph() throws Exception {
        Spanned text = (Spanned) layout(kim()).getText();
        int at = kim().text.indexOf('\u2086');
        DocxTextLayout.WordScriptSpan[] spans =
                text.getSpans(at, at + 1, DocxTextLayout.WordScriptSpan.class);
        assertEquals(1, spans.length);
        assertTrue(spans[0].isUnicode());
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setTextSize(16f);
        float expected = (float) Math.ceil(paint.measureText(text, at, at + 1));
        assertEquals(expected, spans[0].getSize(paint, text, at, at + 1, null), 0.01f);
    }

    private StaticLayout layout(DocxDocument.ParagraphBlock paragraph) {
        DocxTextLayout.initialize(RuntimeEnvironment.getApplication());
        DocxDocument document = document();
        int grid = document.sections.get(2).lineGridPitchTwips;
        float width = new PageGeometry(document.sections.get(2)).contentWidth;
        return DocxTextLayout.measure(paragraph, width, null, grid).layout;
    }

    private DocxDocument.ParagraphBlock kim() {
        return paragraphStartingWith("Kim\u7b49\u5c06\u83b2\u82b1\u578b");
    }

    private DocxDocument.ParagraphBlock zai() {
        return paragraphStartingWith("\u5728\u8fde\u63a5\u8fc7\u7a0b\u4e2d\uff0c");
    }

    private DocxDocument.ParagraphBlock paragraphStartingWith(String prefix) {
        for (DocxDocument.ParagraphBlock paragraph : document().paragraphs)
            if (paragraph.text != null && paragraph.text.startsWith(prefix)) return paragraph;
        throw new AssertionError("paragraph not found: " + prefix);
    }

    private DocxDocument document() {
        try {
            File input = new File(System.getProperty("app.root"), "tests/samples/input-liu.docx");
            return DocxParser.parse(new FileInputStream(input), input.getName());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

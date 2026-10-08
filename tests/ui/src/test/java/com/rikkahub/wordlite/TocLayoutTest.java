package com.rikkahub.wordlite;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import java.io.File;
import java.io.FileInputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class TocLayoutTest {
    @Test public void generatedTocUsesWordSectionGridForDotLeaderRows() throws Exception {
        File input = new File(System.getProperty("app.root"), "tests/samples/input-liu.docx");
        DocxDocument document = DocxParser.parse(new FileInputStream(input), input.getName());
        DocxDocument.ParagraphBlock entry = document.paragraphs.get(20);
        assertEquals(360, document.sections.get(1).lineGridPitchTwips);
        assertEquals(288, entry.format.lineSpacingTwips);
        assertFalse(entry.format.snapToGrid);
        assertEquals(8164, entry.format.rightTabTwips);
        assertEquals("dot", entry.format.tabLeader);
        DocxTextLayout.Paragraph measured = DocxTextLayout.measure(entry,
                new PageGeometry(document.sections.get(1)).contentWidth, null,
                document.sections.get(1).lineGridPitchTwips);
        assertEquals("TOC entry baseline follows 360-twip Word grid", 24,
                measured.layout.getHeight());
    }

    @Test public void tocPageBreakMatchesWordReferenceBoundary() throws Exception {
        File input = new File(System.getProperty("app.root"), "tests/samples/input-liu.docx");
        DocxDocument document = DocxParser.parse(new FileInputStream(input), input.getName());
        A4Paginator.PageResult result = new A4Paginator(document.section).paginate(document);
        A4Paginator.PageContent firstToc = null, secondToc = null;
        for (A4Paginator.PageContent page : result.pages) {
            if (page.sectionIndex != 1) continue;
            if (firstToc == null) firstToc = page;
            else { secondToc = page; break; }
        }
        assertNotNull(firstToc);
        assertNotNull(secondToc);
        assertTrue(containsBlock(firstToc, 51)); // 4.2 预期目标
        assertFalse(containsBlock(firstToc, 52)); // 5 must begin on page 2
        assertTrue(containsBlock(secondToc, 52));
    }

    /**
     * styledText() is what the reading view and the editor lay out. The leader used to be attached
     * only inside measure(), so those two views had neither the dot leader nor the right tab stop:
     * the page number landed on Android's own default tab stop, which is the report of "the numbers
     * in the table of contents sit in the wrong place". Word pins the number's right edge on the
     * declared stop (w:tab val="right" pos=8164 = 408.2pt = 544.27 document px) in every view.
     */
    @Test public void styledTextCarriesTheSameLeaderAsThePagedView() throws Exception {
        File input = new File(System.getProperty("app.root"), "tests/samples/input-liu.docx");
        DocxDocument document = DocxParser.parse(new FileInputStream(input), input.getName());
        DocxDocument.ParagraphBlock entry = document.paragraphs.get(20);
        SpannableStringBuilder text = DocxTextLayout.styledText(entry);
        int tab = text.toString().indexOf('\t');
        assertTrue("a TOC entry carries a tab", tab >= 0);
        assertEquals("one span owns the tab", 1, text.getSpans(tab, tab + 1, ReplacementSpan.class).length);
        assertEquals("LeaderTab", spanNameAt(text, tab));
        Object leader = spanAt(text, tab);
        java.lang.reflect.Field stop = leader.getClass().getDeclaredField("stopPx");
        stop.setAccessible(true);
        float indent = PageGeometry.twips(entry.format.leftIndentTwips == -1
                ? 0 : entry.format.leftIndentTwips);
        assertEquals("the leader pins on the declared stop minus the paragraph indent",
                Math.round(PageGeometry.twips(8164) - indent), stop.getInt(leader));
    }

    /** A paragraph without a declared stop must not pick up a leader by accident. */
    @Test public void bodyParagraphGetsNoLeaderSpan() throws Exception {
        File input = new File(System.getProperty("app.root"), "tests/samples/input-liu.docx");
        DocxDocument document = DocxParser.parse(new FileInputStream(input), input.getName());
        int checked = 0;
        for (DocxDocument.ParagraphBlock paragraph : document.paragraphs) {
            if (paragraph.format.rightTabTwips > 0) continue;
            SpannableStringBuilder text = DocxTextLayout.styledText(paragraph);
            for (ReplacementSpan span : text.getSpans(0, text.length(), ReplacementSpan.class))
                assertFalse("a body paragraph grew a " + span.getClass().getSimpleName(),
                        "LeaderTab".equals(span.getClass().getSimpleName()));
            checked++;
            if (checked >= 40) break;
        }
        assertTrue("several body paragraphs checked", checked >= 40);
    }

    /**
     * A stop is not spent once per paragraph. Word 16.0 measured on the "<num><TAB><title><TAB><page>"
     * variant (artifacts/agent-layout-fix/toc1/word2tab: its own PDF + a character dump of the same
     * page): entry 1.1 lays the title out to END on the 408.25pt stop on line 1, and the page number,
     * pushed onto line 2, ends on that SAME stop with dots in front of it again. So the second tab
     * keeps the declared stop and is measured from its own line start -- StaticLayout measures a span
     * once and never says which line it landed on.
     */
    @Test public void secondTabKeepsTheDeclaredStopOnItsOwnLine() throws Exception {
        DocxDocument.ParagraphBlock entry = new DocxDocument.ParagraphBlock();
        entry.text = "1.1\t  课题背景\t1";
        entry.format.rightTabTwips = 8164;
        entry.format.tabLeader = "dot";
        entry.format.leftIndentTwips = 397;
        SpannableStringBuilder text = DocxTextLayout.styledText(entry);
        int first = text.toString().indexOf('\t');
        int second = text.toString().indexOf('\t', first + 1);
        assertTrue("two tabs in the entry", first >= 0 && second > first);
        assertEquals("LeaderTab", spanNameAt(text, first));
        assertEquals("LeaderTab", spanNameAt(text, second));
        float indent = PageGeometry.twips(entry.format.leftIndentTwips);
        float stop = Math.round(PageGeometry.twips(8164) - indent);
        assertEquals("both tabs pin on the same declared stop", stop, stopPxAt(text, first), 0f);
        assertEquals("both tabs pin on the same declared stop", stop, stopPxAt(text, second), 0f);
        assertFalse("the first tab starts from the paragraph start", ownLineAt(text, first));
        assertTrue("the tab behind it is measured from its own line", ownLineAt(text, second));
    }

    private static boolean containsBlock(A4Paginator.PageContent page, int index) {
        for (A4Paginator.ParagraphLayout paragraph : page.paragraphs)
            if (paragraph.blockIndex == index) return true;
        return false;
    }

    private static String spanNameAt(CharSequence text, int at) {
        ReplacementSpan[] spans = ((Spanned) text).getSpans(at, at + 1, ReplacementSpan.class);
        return spans.length == 1 ? spans[0].getClass().getSimpleName() : spans.length + " spans";
    }

    private static ReplacementSpan spanAt(CharSequence text, int at) {
        ReplacementSpan[] spans = ((Spanned) text).getSpans(at, at + 1, ReplacementSpan.class);
        assertEquals(1, spans.length);
        return spans[0];
    }

    private static float stopPxAt(CharSequence text, int at) throws Exception {
        java.lang.reflect.Field f = spanAt(text, at).getClass().getDeclaredField("stopPx");
        f.setAccessible(true);
        return f.getInt(spanAt(text, at));
    }

    private static boolean ownLineAt(CharSequence text, int at) throws Exception {
        Object span = spanAt(text, at);
        java.lang.reflect.Field f = span.getClass().getDeclaredField("ownLine");
        f.setAccessible(true);
        return f.getBoolean(span);
    }
}
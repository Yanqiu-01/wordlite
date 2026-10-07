package com.rikkahub.wordlite;

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

    private static boolean containsBlock(A4Paginator.PageContent page, int index) {
        for (A4Paginator.ParagraphLayout paragraph : page.paragraphs)
            if (paragraph.blockIndex == index) return true;
        return false;
    }
}

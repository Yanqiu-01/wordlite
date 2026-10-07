package com.rikkahub.wordlite;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Table geometry through the real shaper: w:tblGrid widths, w:trHeight and w:vAlign on the page. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class TableGeometryTest {
    private static DocxDocument real() throws Exception {
        File input = new File(System.getProperty("app.root"), "tests/samples/input-liu.docx");
        return DocxParser.parse(new FileInputStream(input), input.getName());
    }

    private static ArrayList<DocxDocument.TableBlock> tablesOf(DocxDocument document) {
        ArrayList<DocxDocument.TableBlock> found = new ArrayList<DocxDocument.TableBlock>();
        for (DocxDocument.Block block : document.blocks)
            if (block instanceof DocxDocument.TableBlock) found.add((DocxDocument.TableBlock) block);
        return found;
    }

    private static Map<Integer, ArrayList<A4Paginator.TableRow>> rowsByTable(A4Paginator.PageResult result) {
        Map<Integer, ArrayList<A4Paginator.TableRow>> rows =
                new HashMap<Integer, ArrayList<A4Paginator.TableRow>>();
        for (A4Paginator.PageContent page : result.pages)
            for (A4Paginator.ParagraphLayout fragment : page.paragraphs) {
                if (fragment.row == null) continue;
                ArrayList<A4Paginator.TableRow> bucket = rows.get(Integer.valueOf(fragment.blockIndex));
                if (bucket == null) {
                    bucket = new ArrayList<A4Paginator.TableRow>();
                    rows.put(Integer.valueOf(fragment.blockIndex), bucket);
                }
                bucket.add(fragment.row);
            }
        return rows;
    }

    @Test public void cellsUseTheirDeclaredGridWidthsAndPinnedRowHeights() throws Exception {
        DocxDocument document = real();
        DocxDocument.TableBlock last = tablesOf(document).get(3);
        A4Paginator.PageResult result = new A4Paginator(document.section).paginate(document);
        ArrayList<A4Paginator.TableRow> rows = rowsByTable(result).get(Integer.valueOf(last.index));
        assertNotNull("the 21 row table reaches the page breaker", rows);
        assertEquals("every row of the last table is laid out", last.rows.size(), rows.size());
        for (A4Paginator.TableRow row : rows) {
            assertEquals("three grid columns", 3, row.cellWidths.length);
            assertEquals("first column keeps 2410 twips", 2410f / 15f, row.cellWidths[0], 0.02f);
            assertEquals("second column keeps 4253 twips", 4253f / 15f, row.cellWidths[1], 0.02f);
            assertEquals("third column keeps 1911 twips", 1911f / 15f, row.cellWidths[2], 0.02f);
            assertEquals("w:trHeight exact pins the row", 454f / 15f, row.height, 0.01f);
        }
        float total = 0f;
        for (float width : rows.get(0).cellWidths) total += width;
        assertEquals("the table honours w:tblW", 8574f / 15f, total, 0.06f);
        assertTrue("columns advance left to right by their own widths",
                rows.get(0).cellLeft[1] > rows.get(0).cellLeft[0]
                        && rows.get(0).cellLeft[2] > rows.get(0).cellLeft[1]);
    }

    @Test public void atLeastRowHeightFloorAndCenteredCellPaddingApply() throws Exception {
        DocxDocument document = real();
        DocxDocument.TableBlock cover = tablesOf(document).get(0);
        A4Paginator.PageResult result = new A4Paginator(document.section).paginate(document);
        ArrayList<A4Paginator.TableRow> rows = rowsByTable(result).get(Integer.valueOf(cover.index));
        assertNotNull(rows);
        assertEquals("the cover table is laid out", cover.rows.size(), rows.size());
        A4Paginator.TableRow first = rows.get(0);
        // hRule=atLeast is a floor, not a pin: this row carries a 18 pt label, so it may grow past
        // 805 twips but must never come out shorter. The exact-rule rows in the other test are pinned.
        assertTrue("w:trHeight atLeast never cuts the row below its floor",
                first.height >= 805f / 15f - 0.01f);
        assertEquals("label column keeps 1952 twips", 1952f / 15f, first.cellWidths[0], 0.02f);
        assertEquals("title column keeps 6354 twips", 6354f / 15f, first.cellWidths[1], 0.02f);
        // Both cells of this row carry w:vAlign=center and w:tcMar top/bottom 130 twips. The label
        // cell decides the row height, so it stays flush on its padding; the shorter title cell has to
        // be pushed down by the centering offset. Ignoring w:vAlign would leave both at the padding.
        A4Paginator.CellParagraph label = null, title = null;
        for (A4Paginator.CellParagraph cell : first.paragraphs) {
            assertTrue("cell text stays inside its row box",
                    cell.y >= 0f && cell.y + cell.text.layout.getHeight() <= first.height + 0.5f);
            if (Math.abs(cell.cellLeft - first.cellLeft[0]) < 0.5f) label = cell;
            else title = cell;
        }
        assertNotNull("the label cell holds text", label);
        assertNotNull("the title cell holds text", title);
        assertEquals("the height-defining cell sits flush on its w:tcMar top padding",
                130f / 15f, label.y, 0.01f);
        assertTrue("w:vAlign center pushes the shorter cell below its own top padding",
                title.y > 130f / 15f + 1f);
    }

    @Test public void tableIsCentredAndMaySpillPastTheMarginsLikeWord() throws Exception {
        DocxDocument document = real();
        DocxDocument.SectionSettings section = document.sections.get(document.sections.size() - 1);
        float contentWidth = new PageGeometry(section).contentWidth;
        DocxDocument.TableBlock last = tablesOf(document).get(3);
        ArrayList<A4Paginator.TableRow> rows =
                rowsByTable(new A4Paginator(document.section).paginate(document)).get(Integer.valueOf(last.index));
        float left = rows.get(0).cellLeft[0];
        float right = left + rows.get(0).cellWidths[0] + rows.get(0).cellWidths[1] + rows.get(0).cellWidths[2];
        assertTrue("a w:jc=center table is not pinned to the left margin", left > 0.5f || left < -0.5f);
        assertTrue("a table wider than the text column spills instead of shrinking",
                right > contentWidth - 1f);
    }
}

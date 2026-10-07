package com.rikkahub.wordlite;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Pixels, not measurements: the drawn column edges have to land on the declared w:tblGrid widths. */
@RunWith(RobolectricTestRunner.class)
// sdk 28 + NATIVE on purpose: robolectric.enabledSdks is pinned to 28 in build.gradle, and a class
// asking for an unenabled sdk is silently never run. NATIVE is the only mode that really rasterises
// here - LEGACY hands back a no-op canvas whose getPixel() is always 00000000.
@Config(manifest = Config.NONE, sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class TableRenderTest {
    private static int grayRun(int x, int y0, int y1, Bitmap bitmap) {
        int best = 0;
        for (int dx = -2; dx <= 2; dx++) {
            int column = 0;
            for (int y = y0; y < y1; y += 2) {
                int pixel = bitmap.getPixel(Math.max(0, Math.min(bitmap.getWidth() - 1, x + dx)), y);
                int r = Color.red(pixel), g = Color.green(pixel), b = Color.blue(pixel);
                if (Math.abs(r - g) < 24 && Math.abs(g - b) < 24 && r > 0x50 && r < 0xC0) column++;
            }
            best = Math.max(best, column);
        }
        return best;
    }

    @Test public void drawnColumnEdgesFollowWtblGridNotAnEvenSplit() throws Exception {
        File input = new File(System.getProperty("app.root"), "tests/samples/input-liu.docx");
        DocxDocument document = DocxParser.parse(new FileInputStream(input), input.getName());
        DocxDocument.TableBlock last = null;
        for (DocxDocument.Block block : document.blocks)
            if (block instanceof DocxDocument.TableBlock) last = (DocxDocument.TableBlock) block;
        assertNotNull("the reference document has tables", last);

        A4Paginator.PageResult result = new A4Paginator(document.section).paginate(document);
        int pageIndex = -1;
        A4Paginator.ParagraphLayout rowFragment = null;
        for (int i = 0; i < result.pages.size() && rowFragment == null; i++)
            for (A4Paginator.ParagraphLayout fragment : result.pages.get(i).paragraphs)
                if (fragment.row != null && fragment.blockIndex == last.index) {
                    pageIndex = i; rowFragment = fragment; break;
                }
        assertTrue("the last table reaches a page", pageIndex >= 0);
        A4Paginator.PageContent content = result.pages.get(pageIndex);
        PageGeometry geometry = content.geometry == null ? result.geometry : content.geometry;

        float scale = 2f;
        Bitmap bitmap = Bitmap.createBitmap(Math.round(geometry.width * scale),
                Math.round(geometry.height * scale), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        PaperPageView view = new PaperPageView(RuntimeEnvironment.getApplication(), document, content,
                geometry, pageIndex + 1, result.totalPages(), (block, offset) -> { });
        view.showRevisions(false);
        view.render(canvas, scale, false, false);

        File out = new File(System.getProperty("app.root"), "artifacts/analysis");
        out.mkdirs();
        File png = new File(out, "table-page-" + (pageIndex + 1) + ".png");
        try (FileOutputStream file = new FileOutputStream(png)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, file);
        }

        int ink = 0;
        for (int y = 0; y < bitmap.getHeight(); y += 3)
            for (int x = 0; x < bitmap.getWidth(); x += 3)
                if (bitmap.getPixel(x, y) != Color.WHITE) ink++;
        assertTrue("the page actually renders (" + ink + " ink samples)", ink > 2000);

        int y0 = Math.round((geometry.top + rowFragment.top + 2f) * scale);
        int y1 = Math.round((geometry.top + rowFragment.top + rowFragment.height - 2f) * scale);
        assertTrue("the row is tall enough to inspect", y1 - y0 > 8);
        A4Paginator.TableRow row = rowFragment.row;
        int span = Math.max(1, y1 - y0);
        int checked = 0;
        for (int c = 0; c < row.cellLeft.length; c++) {
            // Edges outside the text column belong to the deliberate w:jc spill and are clipped
            // away when drawing, so only the interior edges are probeable here.
            if (row.cellLeft[c] < 0.5f || row.cellLeft[c] > geometry.contentWidth - 2f) continue;
            int edge = Math.round((geometry.left + row.cellLeft[c]) * scale);
            assertTrue("cell " + c + " left border is drawn at its declared w:tblGrid edge ("
                    + row.cellLeft[c] + ")", grayRun(edge, y0, y1, bitmap) > span / 4);
            checked++;
        }
        assertTrue("enough interior column edges to judge", checked >= 2);
        // A table wider than the text column keeps spilling into the page margins, so its outer
        // borders have to survive as well; clipping the row at the column used to erase both.
        float tableRight = row.cellLeft[row.cellLeft.length - 1]
                + row.cellWidths[row.cellWidths.length - 1];
        assertTrue("this table really is wider than the text column",
                tableRight > geometry.contentWidth && row.cellLeft[0] < 0f);
        assertTrue("left outer border is drawn in the margin", grayRun(
                Math.round((geometry.left + row.cellLeft[0]) * scale), y0, y1, bitmap) > span / 4);
        assertTrue("right outer border is drawn in the margin", grayRun(
                Math.round((geometry.left + tableRight) * scale), y0, y1, bitmap) > span / 4);
        // Before the fix every cell was contentWidth / columns, so an edge sat here instead.
        int evenSplit = Math.round((geometry.left + geometry.contentWidth / row.cellLeft.length) * scale);
        assertTrue("the old even split is not where a border now sits",
                Math.abs(evenSplit - Math.round((geometry.left + row.cellLeft[0]) * scale)) > 8);
        int declaredSecond = Math.round((geometry.left + row.cellLeft[1]) * scale);
        assertTrue("the declared grid really is uneven", Math.abs(evenSplit - declaredSecond) > 8);
        assertEquals("no border is drawn where the old even split used to be",
                0, grayRun(evenSplit, y0, y1, bitmap));
    }
}

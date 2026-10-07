package com.rikkahub.wordlite;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.text.StaticLayout;
import java.io.File;
import java.io.FileInputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/**
 * w:jc="both" has to look like Word: a full line ends on the right text edge and the paragraph's
 * last line does not. Android below API 34 cannot open the gaps between characters at all
 * (measured on the target phone: INTER_CHARACTER is inert for Chinese and letterSpacing is
 * quantised), so DocxTextLayout spreads each line's slack over its own gaps; these tests pin that
 * the right edge lands, the last line stays short, and no line break ever moved.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JustifyLayoutTest {

    private static DocxDocument document() throws Exception {
        File input = new File(System.getProperty("app.root"), "tests/samples/input-liu.docx");
        return DocxParser.parse(new FileInputStream(input), input.getName());
    }

    @Test public void justifiedLinesEndOnTheRightEdgeWithoutMovingBreaks() throws Exception {
        DocxDocument doc = document();
        DocxDocument.ParagraphBlock body = null;
        for (DocxDocument.ParagraphBlock p : doc.paragraphs) {
            if (p.format.alignment == 3 && p.text.length() > 120) { body = p; break; }
        }
        assertNotNull("the reference thesis has long justified paragraphs", body);

        float width = 566.9333f;   // 15.00 cm column at 96 dpi, as the section declares
        // w:overflowPunct may legitimately move a break, so take it out of this comparison:
        // here the only difference between the two measurements may be the justification pass.
        boolean keptPunct = body.format.overflowPunct;
        int kept = body.format.alignment;
        body.format.overflowPunct = false;
        StaticLayout justified = DocxTextLayout.measure(body, width).layout;
        body.format.alignment = 0;
        StaticLayout leftAligned = DocxTextLayout.measure(body, width).layout;
        body.format.alignment = kept;
        body.format.overflowPunct = keptPunct;

        assertTrue("the sample paragraph wraps into several lines", justified.getLineCount() >= 3);
        assertEquals("justification must not reflow the paragraph",
                leftAligned.getLineCount(), justified.getLineCount());
        int edge = justified.getLineCount() - 1;
        for (int line = 0; line < justified.getLineCount(); line++) {
            assertEquals("line " + line + " still starts at the same character",
                    leftAligned.getLineStart(line), justified.getLineStart(line));
            float available = justified.getWidth() - justified.getLineLeft(line);
            float used = justified.getLineWidth(line);
            if (line == edge)
                assertTrue("the paragraph's last line stays short (" + used + ")", used < available - 40f);
            else
                assertTrue("line " + line + " should reach the edge (" + used + "/" + available + ")",
                        used >= available - 1f);
        }
    }

    @Test public void drawnJustifiedLineInksUpToTheMargin() throws Exception {
        DocxDocument doc = document();
        A4Paginator.PageResult result = new A4Paginator(doc.section).paginate(doc);
        float scale = 2f;
        for (int i = 0; i < result.pages.size(); i++) {
            A4Paginator.PageContent content = result.pages.get(i);
            PageGeometry g = content.geometry == null ? result.geometry : content.geometry;
            A4Paginator.ParagraphLayout target = null;
            for (A4Paginator.ParagraphLayout f : content.paragraphs)
                if (f.text != null && f.text.source.format.alignment == 3
                        && f.endLine - f.startLine >= 3
                        && f.startLine < f.text.layout.getLineCount() - 1) {
                    target = f;
                    break;
                }
            if (target == null) continue;
            Bitmap bitmap = Bitmap.createBitmap(Math.round(g.width * scale),
                    Math.round(g.height * scale), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);
            PaperPageView view = new PaperPageView(RuntimeEnvironment.getApplication(), doc,
                    content, g, i + 1, result.totalPages(), (block, offset) -> { });
            view.showRevisions(false);
            view.render(canvas, scale, false, false);
            StaticLayout layout = target.text.layout;
            int line = target.startLine;
            int top = Math.round((g.top + target.top - layout.getLineTop(target.startLine)
                    + layout.getLineTop(line)) * scale) + 2;
            int bottom = Math.round((g.top + target.top - layout.getLineTop(target.startLine)
                    + layout.getLineBottom(line)) * scale) - 2;
            int margin = Math.round((g.left + g.contentWidth) * scale);
            int right = -1;
            for (int x = bitmap.getWidth() - 1; x > 0; x--) {
                for (int y = top; y < bottom; y += 2) {
                    int pixel = bitmap.getPixel(x, y);
                    if (pixel != Color.WHITE && Color.alpha(pixel) > 40) { right = x; break; }
                }
                if (right >= 0) break;
            }
            assertTrue("a justified line was rendered", right > 0);
            // The last glyph keeps its right side bearing, so ink stops a little inside the advance.
            assertTrue("ink should reach the right margin (" + right + " of " + margin + ")",
                    right >= margin - 8 && right <= margin + 2);
            return;
        }
        fail("no justified multi-line paragraph found");
    }

    @Test public void tocPageNumberEndsOnTheDeclaredTabStop() throws Exception {
        DocxDocument doc = document();
        A4Paginator.PageResult result = new A4Paginator(doc.section).paginate(doc);
        int checked = 0;
        for (A4Paginator.PageContent content : result.pages) {
            for (A4Paginator.ParagraphLayout f : content.paragraphs) {
                if (f.text == null || f.text.source.format.rightTabTwips <= 0) continue;
                StaticLayout layout = f.text.layout;
                // Word measures a tab stop from the left text margin, so the page number has to END
                // there in document coordinates however far the TOC entry itself is indented.
                float stop = PageGeometry.twips(f.text.source.format.rightTabTwips);
                String line = layout.getText().toString();
                for (int i = f.startLine; i < f.endLine; i++) {
                    // A long heading wraps, and then only the second line carries the tab.
                    int tabAt = line.indexOf('\t', layout.getLineStart(i));
                    if (tabAt < 0 || tabAt >= layout.getLineEnd(i)) continue;
                    float right = f.text.x + layout.getLineRight(i);
                    if (Math.abs(right - stop) > 1.5f)
                        System.out.println("TOCFRAG idx=" + i + " right=" + right + " stop=" + stop
                                + " paraX=" + f.text.x + " lineLeft=" + layout.getLineLeft(i)
                                + " lineWidth=" + layout.getLineWidth(i)
                                + " leftTwips=" + f.text.source.format.leftIndentTwips
                                + " firstTwips=" + f.text.source.format.firstLineIndentTwips);
                    assertTrue("the page number must end on the tab stop, not start there ("
                                    + right + " vs " + stop + ")",
                            Math.abs(right - stop) <= 1.5f);
                    checked++;
                }
            }
        }
        assertTrue("the reference document carries a table of contents", checked >= 20);
    }
}

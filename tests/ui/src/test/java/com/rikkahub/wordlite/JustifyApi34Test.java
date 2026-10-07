package com.rikkahub.wordlite;

import android.os.Build;
import android.text.StaticLayout;
import java.io.File;
import java.io.FileInputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/**
 * w:jc="both" has to hold on API 34 too, where DocxTextLayout used to trust the platform. Android 14
 * is the first release with Layout.JUSTIFICATION_MODE_INTER_CHARACTER, yet measured here (android-all
 * 34 with native minikin) the platform pass alone leaves 7, 8, 23, 23, 11 and 7 px of a 566.93 px
 * column unspread across the six full lines of the first long paragraph of the reference thesis --
 * a visibly ragged right edge, which is the exact complaint this whole pass exists to answer. So the
 * app spreads whatever the platform left, and this pins both halves: the line reaches the edge
 * without overshooting, and no break moved.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class JustifyApi34Test {

    private static final float COLUMN_WIDTH = 566.9333f;   // 15.00 cm at 96 dpi, as the section declares

    @Test public void fullChineseLinesReachTheEdgeOnApi34Too() throws Exception {
        assertEquals("this class exists to run the API 34 branch", 34, Build.VERSION.SDK_INT);
        File input = new File(System.getProperty("app.root"), "tests/samples/input-liu.docx");
        DocxDocument doc = DocxParser.parse(new FileInputStream(input), input.getName());
        DocxDocument.ParagraphBlock body = null;
        for (DocxDocument.ParagraphBlock p : doc.paragraphs)
            if (p.format.alignment == 3 && p.text.length() > 120) { body = p; break; }
        assertNotNull("the reference thesis has long justified paragraphs", body);

        // w:overflowPunct may legitimately move a break, so the only difference allowed here is
        // who stretched the line.
        boolean keptPunct = body.format.overflowPunct;
        int kept = body.format.alignment;
        body.format.overflowPunct = false;
        StaticLayout justified = DocxTextLayout.measure(body, COLUMN_WIDTH).layout;
        body.format.alignment = 0;
        StaticLayout leftAligned = DocxTextLayout.measure(body, COLUMN_WIDTH).layout;
        body.format.alignment = kept;
        body.format.overflowPunct = keptPunct;

        assertTrue("the sample paragraph wraps into several lines", justified.getLineCount() >= 3);
        assertEquals("justification must not reflow the paragraph",
                leftAligned.getLineCount(), justified.getLineCount());
        int last = justified.getLineCount() - 1;
        float worst = 0f;
        int worstLine = -1;
        for (int line = 0; line <= last; line++) {
            assertEquals("line " + line + " still starts at the same character",
                    leftAligned.getLineStart(line), justified.getLineStart(line));
            float available = justified.getWidth() - justified.getLineLeft(line);
            float used = justified.getLineWidth(line);
            if (line == last) {
                assertTrue("the paragraph's last line stays short (" + used + ")",
                        used < available - 40f);
                continue;
            }
            System.out.println("API34 line " + line + " used=" + used + " slack=" + (available - used));
            assertTrue("line " + line + " should reach the edge (" + used + "/" + available + ")",
                    used >= available - 1f);
            assertTrue("line " + line + " must not overshoot the column (" + used + "/" + available + ")",
                    used <= available + 1f);
            if (available - used > worst) { worst = available - used; worstLine = line; }
        }
        System.out.println("API34 worst non-last slack=" + worst + " px at line " + worstLine
                + " of " + justified.getLineCount() + " lines");
    }
}

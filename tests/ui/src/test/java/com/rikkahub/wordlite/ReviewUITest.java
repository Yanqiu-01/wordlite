package com.rikkahub.wordlite;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ReviewUITest {
    @Test public void standardReviewPaneShowsRepliesAndAcceptReject() {
        DocxDocument document = new DocxDocument();
        DocxDocument.ParagraphBlock p = new DocxDocument.ParagraphBlock(); p.index = 0; p.text = "论文";
        p.runs.add(new DocxDocument.Run(p.text, p.baseRunStyle.copy())); document.paragraphs.add(p); document.blocks.add(p);
        DocxDocument.Comment comment = ReviewManager.comment(document, 0, 0, 1, "批注正文");
        ReviewManager.reply(document, comment, "批注回复");
        document.trackRevisions = true; ReviewManager.format(document, p, 0, 1);
        ReviewPanel pane = new ReviewPanel(RuntimeEnvironment.getApplication());
        boolean[] decided = {false};
        pane.bind(document, 0xFFFFFFFF, 0xFF000000, new ReviewPanel.Listener() {
            public void navigate(int paragraph, int offset) { }
            public void decide(DocxDocument.Revision revision, boolean accept) { decided[0] = accept; }
            public void reply(DocxDocument.Comment comment) { }
            public void resolve(DocxDocument.Comment comment) { }
            public void author() { }
        });
        assertNotNull(find(pane, "批注正文")); assertNotNull(find(pane, "批注回复"));
        find(pane, "接受").performClick(); assertTrue(decided[0]);
        assertNotNull(find(pane, "拒绝")); assertNotNull(find(pane, "解决"));
    }
    @Test public void markedCopyNeverChangesSourceOrItsAnchors() {
        DocxDocument document = new DocxDocument(); DocxDocument.ParagraphBlock p = new DocxDocument.ParagraphBlock();
        p.index = 0; p.text = "abcd"; p.runs.add(new DocxDocument.Run(p.text, p.baseRunStyle.copy()));
        document.paragraphs.add(p); document.blocks.add(p); document.trackRevisions = true;
        ReviewManager.recordEdit(document, p, 1, 1, "X"); p.text = "aXcd"; p.runs.clear(); p.runs.add(new DocxDocument.Run(p.text, p.baseRunStyle.copy()));
        DocxDocument marked = ReviewCopies.marked(document);
        assertEquals("abXcd", marked.paragraphs.get(0).text); assertEquals("aXcd", p.text);
        assertEquals(2, document.revisions.size());
        boolean strike = false; for (DocxDocument.Run run : marked.paragraphs.get(0).runs) strike |= run.style.strike;
        assertTrue(strike);
    }
    private TextView find(View view, String text) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(text)) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { TextView result = find(group.getChildAt(i), text); if (result != null) return result; }
        }
        return null;
    }
}

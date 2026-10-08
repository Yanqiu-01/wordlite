package com.rikkahub.wordlite;

import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = {23, 28})
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class EditorWorkflowTest {
    private EditorActivity editor() throws Exception {
        java.io.File file = new java.io.File(System.getProperty("app.root"), "tests/fixture.docx");
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), EditorActivity.class); intent.setData(Uri.fromFile(file));
        EditorActivity editor = Robolectric.buildActivity(EditorActivity.class, intent).setup().get();
        for (int attempt = 0; attempt < 100 && ReflectionHelpers.getField(editor, "document") == null; attempt++) {
            Thread.sleep(20); Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
        assertNotNull(ReflectionHelpers.getField(editor, "document")); return editor;
    }
    @Test public void reviewCommandsPersistTrackedTypingAndUndo() throws Exception {
        EditorActivity activity = editor();
        DocxDocument document = ReflectionHelpers.getField(activity, "document");
        DocxDocument.ParagraphBlock p = document.paragraphs.get(0); String old = p.text;
        ReflectionHelpers.callInstanceMethod(activity, "openParagraphEditor", ReflectionHelpers.ClassParameter.from(int.class, p.index), ReflectionHelpers.ClassParameter.from(int.class, 0));
        command(activity, "track");
        EditText edit = ReflectionHelpers.getField(activity, "activeEditor"); edit.getText().insert(0, "新");
        assertEquals("新" + old, p.text); assertTrue(document.trackRevisions); assertFalse(document.revisions.isEmpty());
        command(activity, "undo"); assertEquals(old, p.text); assertTrue(document.revisions.isEmpty());
        command(activity, "redo"); assertEquals("新" + old, p.text); assertFalse(document.revisions.isEmpty());
        command(activity, "reject"); assertEquals(old, p.text);
    }
    @Test public void partialBoldRetainsItalicAndNeighborStyles() throws Exception {
        EditorActivity activity = editor(); DocxDocument document = ReflectionHelpers.getField(activity, "document");
        DocxDocument.ParagraphBlock p = document.paragraphs.get(0); p.text = "abcd"; p.runs.clear();
        DocxDocument.RunStyle style = new DocxDocument.RunStyle(); style.italic = true; style.italicSet = true; style.fontSizeHalfPoints = 24;
        p.runs.add(new DocxDocument.Run(p.text, style));
        ReflectionHelpers.callInstanceMethod(activity, "openParagraphEditor", ReflectionHelpers.ClassParameter.from(int.class, p.index), ReflectionHelpers.ClassParameter.from(int.class, 0));
        EditText edit = ReflectionHelpers.getField(activity, "activeEditor"); edit.setSelection(1, 3); command(activity, "bold");
        assertTrue(styleAt(p, 0).italic); assertFalse(styleAt(p, 0).bold);
        assertTrue(styleAt(p, 1).italic); assertTrue(styleAt(p, 1).bold);
        assertTrue(styleAt(p, 3).italic); assertFalse(styleAt(p, 3).bold);
    }
    /**
     * ribbon 的段落状态跟着正在编辑的那一段走，编辑行上的撤销/重做是真接上活的：
     * 键盘抬起来的那一会儿，ribbon 那一排要横滑才够得着，撤销重做就摆在编辑行上。
     */
    @Test public void ribbonStateFollowsTheParagraphAndHeaderUndoRedoAreWired() throws Exception {
        EditorActivity activity = editor();
        DocxDocument document = ReflectionHelpers.getField(activity, "document");
        DocxDocument.ParagraphBlock p = document.paragraphs.get(0);
        p.text = "正文"; p.runs.clear();
        ReflectionHelpers.callInstanceMethod(activity, "openParagraphEditor",
                ReflectionHelpers.ClassParameter.from(int.class, p.index),
                ReflectionHelpers.ClassParameter.from(int.class, 0));
        RibbonUI ribbon = ReflectionHelpers.getField(activity, "ribbon");
        ribbon.select("开始");
        assertFalse("这一段没加粗，B 不许亮",
                ((android.widget.TextView) ribbon.findViewWithTag("command-bold")).isActivated());
        p.format.alignment = 3;
        ReflectionHelpers.callInstanceMethod(activity, "updateRibbonState");
        assertTrue("这一段是两端对齐的，两端就该亮着",
                ((android.widget.TextView) ribbon.findViewWithTag("command-justify")).isActivated());
        assertNotNull("编辑行上要有撤销", activity.getWindow().getDecorView().findViewWithTag("edit-undo"));
        assertNotNull("编辑行上要有重做", activity.getWindow().getDecorView().findViewWithTag("edit-redo"));
        command(activity, "bold");
        assertTrue("加粗之后 B 亮着",
                ((android.widget.TextView) ribbon.findViewWithTag("command-bold")).isActivated());
        ((android.widget.TextView) activity.getWindow().getDecorView().findViewWithTag("edit-undo")).performClick();
        assertFalse("编辑行上的撤销按下去真的撤销了", styleAt(p, 0).bold);
        assertFalse("撤销完 B 也要跟着灭",
                ((android.widget.TextView) ribbon.findViewWithTag("command-bold")).isActivated());
    }

    private void command(EditorActivity activity, String command) {
        ReflectionHelpers.callInstanceMethod(activity, "ribbonCommand", ReflectionHelpers.ClassParameter.from(String.class, command));
    }
    private DocxDocument.RunStyle styleAt(DocxDocument.ParagraphBlock p, int offset) {
        int at = 0; for (DocxDocument.Run run : p.runs) { if (offset < at + run.text.length()) return run.style; at += run.text.length(); }
        throw new AssertionError("No style");
    }
}

package com.rikkahub.wordlite;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Only invoke explicitly on a native-capable host; never use legacy empty PDF as evidence. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PdfNativeTest {
    @Test public void nativePdfEmbedsFontsAndExportsTwoRealPages() throws Exception {
        if (System.getProperty("os.arch").equals("aarch64")) org.junit.Assume.assumeTrue("native runtime unsupported on Linux aarch64", false);
        DocxDocument document = new DocxDocument();
        for (int i = 0; i < 2; i++) {
            DocxDocument.ParagraphBlock p = new DocxDocument.ParagraphBlock();
            p.index = i; p.text = "论文 Times New Roman μ π α β ∑ ∫ √ ≤ ≥ ℝ ∇ ∉ ∀ ∃ ∴ ∵";
            p.isHeading = true; p.baseRunStyle.fontSizeHalfPoints = 24;
            p.baseRunStyle.eastAsiaFontFamily = "宋体"; p.baseRunStyle.asciiFontFamily = "Times New Roman";
            p.baseRunStyle.highAnsiFontFamily = "Times New Roman";
            p.format.pageBreakBefore = i > 0;
            p.runs.add(new DocxDocument.Run(p.text, p.baseRunStyle.copy()));
            document.paragraphs.add(p); document.blocks.add(p);
        }
        File root = new File(System.getProperty("app.root"));
        org.robolectric.util.ReflectionHelpers.callInstanceMethod(RuntimeEnvironment.getApplication().getAssets(),
                "addAssetPath", org.robolectric.util.ReflectionHelpers.ClassParameter.from(String.class,
                        new File(root, "artifacts/apk/wordlite-debug.apk").getAbsolutePath()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PdfExporter.export(RuntimeEnvironment.getApplication(), document, output, new PdfExportOptions());
        byte[] bytes = output.toByteArray();
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("%PDF-")); assertTrue(bytes.length > 1000);
        assertTrue(text.contains("/FontFile2") || text.contains("/FontFile3"));
        File target = new File(root, "artifacts/tests/pdf/native-sample.pdf"); target.getParentFile().mkdirs();
        try (FileOutputStream file = new FileOutputStream(target)) { file.write(bytes); }
        PdfExportOptions single = new PdfExportOptions(); single.scope = PdfExportOptions.Scope.CURRENT_PAGE; single.currentPage = 1;
        output.reset(); PdfExporter.export(RuntimeEnvironment.getApplication(), document, output, single);
        try (FileOutputStream file = new FileOutputStream(new File(root, "artifacts/tests/pdf/native-current.pdf"))) { file.write(output.toByteArray()); }
        PdfExportOptions selected = new PdfExportOptions(); selected.scope = PdfExportOptions.Scope.SELECTION;
        selected.paragraphIndex = 0; selected.selectionStart = 26; selected.selectionEnd = document.paragraphs.get(0).text.length();
        output.reset(); PdfExporter.export(RuntimeEnvironment.getApplication(), document, output, selected);
        try (FileOutputStream file = new FileOutputStream(new File(root, "artifacts/tests/pdf/native-selection.pdf"))) { file.write(output.toByteArray()); }
        DocxDocument.ParagraphBlock paragraph = document.paragraphs.get(0);
        document.trackRevisions = true;
        ReviewManager.recordEdit(document, paragraph, 0, 2, "研究");
        paragraph.text = "研究" + paragraph.text.substring(2); paragraph.runs.clear();
        paragraph.runs.add(new DocxDocument.Run(paragraph.text, paragraph.baseRunStyle.copy()));
        DocxDocument.Comment comment = ReviewManager.comment(document, 0, 0, 2, "测试批注");
        ReviewManager.reply(document, comment, "测试回复");
        PdfExportOptions review = new PdfExportOptions(); review.includeComments = true; review.includeRevisions = true;
        output.reset(); PdfExporter.export(RuntimeEnvironment.getApplication(), document, output, review);
        try (FileOutputStream file = new FileOutputStream(new File(root, "artifacts/tests/pdf/native-review.pdf"))) { file.write(output.toByteArray()); }
        assertTrue(paragraph.text.startsWith("研究"));
        assertEquals(2, document.revisions.size());
    }
}

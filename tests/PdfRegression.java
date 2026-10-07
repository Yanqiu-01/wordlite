package com.rikkahub.wordlite;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Range/copy and standards metadata tested without faking Android PDF drawing. */
public final class PdfRegression {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++; System.out.println("PASS " + message);
    }
    public static void main(String[] args) throws Exception {
        PdfExportOptions options = new PdfExportOptions();
        check(options.pages(3).length == 3, "full export page range");
        options.scope = PdfExportOptions.Scope.CURRENT_PAGE; options.currentPage = 1;
        check(options.pages(3)[0] == 1 && options.pages(3).length == 1, "current page range");
        boolean rejected = false;
        try { options.currentPage = 4; options.pages(2); } catch (IllegalArgumentException error) { rejected = true; }
        check(rejected, "invalid page range rejected");
        DocxDocument document = new DocxDocument();
        DocxDocument.ParagraphBlock paragraph = new DocxDocument.ParagraphBlock();
        paragraph.index = 9; paragraph.text = "前文αβ后文";
        paragraph.baseRunStyle.fontSizeHalfPoints = 24;
        paragraph.runs.add(new DocxDocument.Run(paragraph.text, paragraph.baseRunStyle.copy()));
        document.blocks.add(paragraph); document.paragraphs.add(paragraph);
        options.scope = PdfExportOptions.Scope.SELECTION; options.paragraphIndex = 9;
        options.selectionStart = 2; options.selectionEnd = 4;
        DocxDocument slice = DocumentSlices.selection(document, options);
        check(slice.paragraphs.get(0).text.equals("αβ"), "selection clone retains Unicode text");
        check(slice.paragraphs.get(0).runs.get(0).style.fontSizeHalfPoints == 24, "selection clone retains formatting");
        check(paragraph.text.equals("前文αβ后文"), "selection export never mutates source");
        options.selectionEnd = 2; rejected = false;
        try { DocumentSlices.selection(document, options); } catch (IllegalArgumentException error) { rejected = true; }
        check(rejected, "empty selection fails rather than exporting whole document");
        String[] objects = {"<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R >>",
                "<< /Length 0 >>\nstream\n\nendstream"};
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n"); int[] offsets = new int[5];
        for (int i = 0; i < objects.length; i++) { offsets[i + 1] = pdf.length(); pdf.append(i + 1).append(" 0 obj\n").append(objects[i]).append("\nendobj\n"); }
        int xref = pdf.length(); pdf.append("xref\n0 5\n0000000000 65535 f \n");
        for (int i = 1; i < 5; i++) pdf.append(String.format(java.util.Locale.US, "%010d 00000 n \n", offsets[i]));
        pdf.append("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        ArrayList<PdfExtras.Bookmark> bookmarks = new ArrayList<PdfExtras.Bookmark>();
        PdfExtras.Bookmark bookmark = new PdfExtras.Bookmark(); bookmark.page = 0; bookmark.top = 720; bookmark.title = "论文 αβ";
        bookmarks.add(bookmark);
        ArrayList<PdfExtras.Annotation> annotations = new ArrayList<PdfExtras.Annotation>();
        PdfExtras.Annotation annotation = new PdfExtras.Annotation(); annotation.page = 0;
        annotation.left = 20; annotation.right = 40; annotation.bottom = 700; annotation.top = 715;
        annotation.uri = "https://example.test/α"; annotations.add(annotation);
        PdfExtras.Annotation comment = new PdfExtras.Annotation(); comment.page = 0;
        comment.text = "批注 μ"; comment.author = "作者"; annotations.add(comment);
        byte[] output = PdfExtras.append(pdf.toString().getBytes(StandardCharsets.ISO_8859_1), bookmarks, annotations);
        String contents = new String(output, StandardCharsets.ISO_8859_1);
        check(contents.contains("/Outlines") && contents.contains("/Subtype /Link"), "standard PDF outlines and links");
        check(contents.contains("/Subtype /Text") && contents.contains("/Prev " + xref), "standard comment and valid incremental predecessor");
        new File(args[0]).getParentFile().mkdirs();
        try (FileOutputStream file = new FileOutputStream(args[0])) { file.write(output); }
        System.out.println("SUMMARY " + checks + " PDF range/metadata assertions passed; Android native rendering not simulated");
    }
}

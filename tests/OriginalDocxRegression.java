package com.rikkahub.wordlite;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;

/** Regression against the uploaded real proposal document. */
public final class OriginalDocxRegression {
    private static int checks;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("input.docx output.docx");
        File input = new File(args[0]);
        File output = new File(args[1]);
        DocxDocument document = DocxParser.parse(new FileInputStream(input), input.getName());

        int superscript = 0, subscript = 0, runCount = 0;
        Set<String> eastAsiaFonts = new HashSet<String>();
        Set<String> latinFonts = new HashSet<String>();
        Set<Integer> sizes = new HashSet<Integer>();
        int imageBytes = 0;
        for (DocxDocument.ParagraphBlock paragraph : document.paragraphs) {
            for (DocxDocument.Run run : paragraph.runs) {
                runCount++;
                if (run.style.superscript) superscript++;
                if (run.style.subscript) subscript++;
                if (run.style.eastAsiaFontFamily != null) eastAsiaFonts.add(run.style.eastAsiaFontFamily);
                if (run.style.asciiFontFamily != null) latinFonts.add(run.style.asciiFontFamily);
                if (run.style.fontSizeHalfPoints > 0) sizes.add(run.style.fontSizeHalfPoints);
            }
            for (DocxDocument.EmbeddedImage image : paragraph.images)
                imageBytes += image.bytes == null ? 0 : image.bytes.length;
        }
        check(document.paragraphs.size() == 373, "real document paragraph count survives CRC-tolerant ZIP reading");
        check(document.sections.size() == 3
                        && document.sections.get(2).marginLeftTwips == 1701
                        && document.sections.get(2).marginRightTwips == 1701
                        && document.sections.get(2).marginTopTwips == 2154
                        && document.sections.get(2).marginBottomTwips == 1701
                        && document.sections.get(2).lineGridPitchTwips == 360,
                "real document section page size, native margins and docGrid are retained");
        boolean lineUnitSpacing = false;
        for (DocxDocument.ParagraphBlock paragraph : document.paragraphs)
            lineUnitSpacing |= paragraph.format.spacingBeforeLines >= 0 || paragraph.format.spacingAfterLines >= 0;
        check(lineUnitSpacing, "real document beforeLines/afterLines paragraph spacing is retained");
        check(document.tableCount == 4, "real document tables are readable");
        check(document.imageCount == 9 && imageBytes > 2000000, "all nine real document images are extracted");
        check(superscript == 29 && subscript == 34, "all real document superscript/subscript runs are retained");
        check(eastAsiaFonts.contains("宋体") && eastAsiaFonts.contains("黑体")
                        && eastAsiaFonts.contains("华文新魏"),
                "real document East Asian font names are retained");
        check(latinFonts.contains("Times New Roman"), "real document Latin font name is retained");
        check(sizes.contains(24) && sizes.contains(30) && sizes.contains(36),
                "real document mixed run sizes are retained");

        // ---- w:ind signedness and per-section w:pgMar (artifacts/agent-layout-fix/spec-notes.md) ----
        // 15 headings write <w:ind w:left="-458" w:firstLine="406"/>: -22.9pt of left indent plus
        // 20.3pt of first line, i.e. Word starts the first line 2.6pt LEFT of the text margin.
        int negativeLeftHeadings = 0;
        for (DocxDocument.ParagraphBlock paragraph : document.paragraphs) {
            DocxDocument.ParagraphFormat f = paragraph.format;
            if (f.leftIndentTwips == -458 && f.firstLineIndentTwips == 406) negativeLeftHeadings++;
        }
        check(negativeLeftHeadings == 15,
                "15 headings keep the negative w:ind/@w:left (-458 twips) through the style merge");
        DocxDocument.ParagraphFormat merged = new DocxDocument.ParagraphFormat();
        merged.leftIndentTwips = 0; merged.rightIndentTwips = 0;
        DocxDocument.ParagraphFormat signed = new DocxDocument.ParagraphFormat();
        signed.leftIndentTwips = -458; signed.rightIndentTwips = -120;
        merged.merge(signed);
        check(merged.leftIndentTwips == -458 && merged.rightIndentTwips == -120,
                "merge() reads -1 as the only unset marker, so a signed w:ind left/right survives it");
        DocxDocument.ParagraphFormat unwritten = new DocxDocument.ParagraphFormat();
        unwritten.leftIndentTwips = -1;
        DocxDocument.ParagraphFormat inherited = new DocxDocument.ParagraphFormat();
        inherited.leftIndentTwips = 700;
        inherited.merge(unwritten);
        check(inherited.leftIndentTwips == 700, "an unwritten w:ind still leaves the inherited indent alone");

        PageGeometry cover = new PageGeometry(document.sections.get(0));
        PageGeometry body = new PageGeometry(document.sections.get(2));
        check(document.sections.get(0).marginLeftTwips == 1800
                        && document.sections.get(0).marginRightTwips == 1800
                        && document.sections.get(0).marginTopTwips == 1440,
                "the cover section carries its own 90pt w:pgMar instead of the body's 85.05pt");
        check(Math.abs(cover.left - 120f) < 0.01f && Math.abs(body.left - 113.4f) < 0.01f
                        && Math.abs(cover.contentWidth - 553.7333f) < 0.02f
                        && Math.abs(body.contentWidth - 566.9333f) < 0.02f,
                "every section's own pgMar drives its own content box (cover 553.73px, body 566.93px)");

        DocxWriter.write(new FileInputStream(input), new FileOutputStream(output), document);
        DocxDocument roundtrip = DocxParser.parse(new FileInputStream(output), output.getName());
        int roundSup = 0, roundSub = 0, roundImages = 0;
        for (DocxDocument.ParagraphBlock paragraph : roundtrip.paragraphs) {
            for (DocxDocument.Run run : paragraph.runs) {
                if (run.style.superscript) roundSup++;
                if (run.style.subscript) roundSub++;
            }
            roundImages += paragraph.images.size();
        }
        check(roundtrip.imageCount == 9 && roundImages == 9, "all nine images survive real document roundtrip");
        check(roundSup == superscript && roundSub == subscript,
                "superscript and subscript survive real document roundtrip");
        check(roundtrip.paragraphs.size() == document.paragraphs.size(),
                "paragraph count survives real document roundtrip");
        System.out.println("SUMMARY " + checks + " real-document assertions passed");
    }
}

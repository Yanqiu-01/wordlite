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

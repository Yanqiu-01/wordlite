package com.rikkahub.wordlite;

import java.io.FileInputStream;

public final class RealLayoutProbe {
    public static void main(String[] args) throws Exception {
        DocxDocument d = DocxParser.parse(new FileInputStream(args[0]), "real.docx");
        int position = 0, sup = 0, sub = 0;
        for (DocxDocument.ParagraphBlock p : d.paragraphs) {
            for (DocxDocument.Run r : p.runs) {
                if (r.style.positionSet) position++;
                if (r.style.superscript) sup++;
                if (r.style.subscript) sub++;
            }
        }
        A4Paginator.PageResult result = new A4Paginator(d.section).paginate(d);
        System.out.println("paragraphs=" + d.paragraphs.size()
                + " images=" + d.imageCount
                + " superscript=" + sup
                + " subscript=" + sub
                + " position=" + position
                + " pages=" + result.totalPages()
                + " imageFragments=" + result.imageFragments);
        if (d.imageCount != 9) throw new AssertionError("image parse count");
        if (result.imageFragments != 9) throw new AssertionError("image layout count=" + result.imageFragments);
        if (sup != 29 || sub != 34) throw new AssertionError("script count");
    }
}

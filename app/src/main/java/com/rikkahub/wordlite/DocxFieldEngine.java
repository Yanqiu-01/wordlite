package com.rikkahub.wordlite;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/** Field evaluation for display. PAGE/NUMPAGES depend on this app's own pagination. */
public final class DocxFieldEngine {
    private DocxFieldEngine() { }

    /**
     * Substitution result for display: override text plus, for every displayed
     * character, the source-text offset it came from (used to keep styles and
     * caret mapping valid when a field result changes length).
     */
    public static final class DisplayMap {
        public String text;
        public int[] sourceIndex; // length = text.length()+1
        public boolean changed;
    }

    public static DisplayMap displayTextWithMap(DocxDocument document, DocxDocument.ParagraphBlock paragraph,
                                                int pageNumber, int pageCount) {
        String source = paragraph.text == null ? "" : paragraph.text;
        StringBuilder out = new StringBuilder();
        ArrayList<Integer> map = new ArrayList<Integer>();
        int src = 0;
        ArrayList<DocxDocument.FieldCode> fields = new ArrayList<DocxDocument.FieldCode>(paragraph.fields);
        java.util.Collections.sort(fields, (a, b) -> Integer.compare(a.start, b.start));
        for (DocxDocument.FieldCode field : fields) {
            if (field.start < 0 || field.end <= field.start || field.start < src || field.start > source.length()) continue;
            for (int i = src; i < field.start; i++) { out.append(source.charAt(i)); map.add(i); }
            String value = evaluate(document, field.instruction, pageNumber, pageCount);
            if (value.length() == 0) value = field.cachedResult;
            // Characters of a substituted result adopt the field's first source offset.
            for (int i = 0; i < value.length(); i++) { out.append(value.charAt(i)); map.add(field.start); }
            src = Math.min(field.end, source.length());
        }
        for (int i = src; i < source.length(); i++) { out.append(source.charAt(i)); map.add(i); }
        map.add(source.length());
        DisplayMap dm = new DisplayMap();
        dm.text = out.toString();
        dm.sourceIndex = new int[map.size()];
        for (int i = 0; i < map.size(); i++) dm.sourceIndex[i] = map.get(i);
        dm.changed = !dm.text.equals(source);
        return dm;
    }

    public static String displayText(DocxDocument document, DocxDocument.ParagraphBlock paragraph,
                                     int pageNumber, int pageCount) {
        String value = paragraph.text == null ? "" : paragraph.text;
        // Offset-bound fields first (precise), legacy fallback by cached-text search.
        boolean anyBound = false;
        for (DocxDocument.FieldCode f : paragraph.fields) if (f.start >= 0) { anyBound = true; break; }
        if (anyBound) return displayTextWithMap(document, paragraph, pageNumber, pageCount).text;
        for (DocxDocument.FieldCode field : paragraph.fields) {
            String evaluated = evaluate(document, field.instruction, pageNumber, pageCount);
            if (evaluated.length() == 0 || field.cachedResult.length() == 0) continue;
            int at = value.indexOf(field.cachedResult);
            if (at >= 0) value = value.substring(0, at) + evaluated
                    + value.substring(at + field.cachedResult.length());
        }
        return value;
    }

    public static String evaluate(DocxDocument document, String instruction,
                                  int pageNumber, int pageCount) {
        if (instruction == null) return "";
        String code = instruction.trim();
        if (code.length() == 0) return "";
        String[] pieces = code.split("\\s+");
        String name = pieces[0].toUpperCase(Locale.US);
        if ("PAGE".equals(name)) return String.valueOf(pageNumber);
        if ("NUMPAGES".equals(name)) return String.valueOf(pageCount);
        if ("DATE".equals(name)) return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if ("TIME".equals(name)) return new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        if ("TITLE".equals(name)) return document.title == null ? "" : document.title;
        if ("FILENAME".equals(name)) return document.fileName == null ? "" : document.fileName;
        if ("AUTHOR".equals(name)) return "";
        if ("DOCPROPERTY".equals(name) && pieces.length > 1) {
            if ("TITLE".equalsIgnoreCase(pieces[1])) return document.title;
            if ("AUTHOR".equalsIgnoreCase(pieces[1])) return "";
        }
        if ("STYLEREF".equals(name) && pieces.length > 1) {
            for (DocxDocument.ParagraphBlock paragraph : document.paragraphs) {
                if (paragraph.isHeading && paragraph.text.trim().length() > 0) return paragraph.text.trim();
            }
        }
        if ("SEQ".equals(name) && pieces.length > 1) {
            // Simple sequential numbering across the document in reading order.
            String label = pieces[1];
            int seq = 0;
            for (DocxDocument.ParagraphBlock p : document.paragraphs) {
                for (DocxDocument.FieldCode f : p.fields) {
                    String f2 = f.instruction.trim();
                    String[] pp = f2.split("\\s+");
                    if (pp.length > 1 && "SEQ".equalsIgnoreCase(pp[0]) && label.equals(pp[1])) {
                        seq++;
                        if (p.index == firstFieldParagraph(document, instruction)) return String.valueOf(seq);
                    }
                }
            }
            return seq > 0 ? String.valueOf(seq) : "";
        }
        // TOC/REF/hyperlink targets need bookmark resolution; cached display stays.
        return "";
    }

    private static int firstFieldParagraph(DocxDocument document, String instruction) {
        for (DocxDocument.ParagraphBlock p : document.paragraphs)
            for (DocxDocument.FieldCode f : p.fields)
                if (f.instruction.trim().equals(instruction.trim())) return p.index;
        return -1;
    }
}

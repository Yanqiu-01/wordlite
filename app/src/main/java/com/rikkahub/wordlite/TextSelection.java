package com.rikkahub.wordlite;

import java.util.ArrayList;

/** Immutable request snapshot, maps service text offsets back to original paragraph ranges. */
public final class TextSelection {
    public static final class Piece {
        public final int paragraphIndex, start, end, submittedStart;
        public final String original;
        Piece(int index, int start, int end, int submittedStart, String original) {
            paragraphIndex = index; this.start = start; this.end = end; this.submittedStart = submittedStart; this.original = original;
        }
    }
    public static final class Range {
        public final int paragraphIndex, start, end;
        Range(int paragraph, int start, int end) { paragraphIndex = paragraph; this.start = start; this.end = end; }
    }
    public final ArrayList<Piece> pieces = new ArrayList<Piece>();
    public final String text;
    private TextSelection(ArrayList<Piece> pieces, String text) { this.pieces.addAll(pieces); this.text = text; }
    public static TextSelection all(DocxDocument document) {
        ArrayList<Piece> pieces = new ArrayList<Piece>(); StringBuilder text = new StringBuilder();
        for (DocxDocument.ParagraphBlock paragraph : document.paragraphs) {
            if (!pieces.isEmpty()) text.append('\n');
            pieces.add(new Piece(paragraph.index, 0, paragraph.text.length(), text.length(), paragraph.text)); text.append(paragraph.text);
        }
        return new TextSelection(pieces, text.toString());
    }
    public static TextSelection paragraph(DocxDocument.ParagraphBlock paragraph, int start, int end) {
        if (paragraph == null || start < 0 || end <= start || end > paragraph.text.length()) throw new IllegalArgumentException("没有选中内容");
        if (start > 0 && Character.isLowSurrogate(paragraph.text.charAt(start)) || end < paragraph.text.length() && Character.isLowSurrogate(paragraph.text.charAt(end)))
            throw new IllegalArgumentException("文字范围无效");
        ArrayList<Piece> pieces = new ArrayList<Piece>(); String text = paragraph.text.substring(start, end);
        pieces.add(new Piece(paragraph.index, start, end, 0, text)); return new TextSelection(pieces, text);
    }
    public ArrayList<Range> ranges(int start, int end) {
        ArrayList<Range> result = new ArrayList<Range>();
        if (start < 0 || end <= start || end > text.length()) return result;
        for (Piece piece : pieces) {
            int lo = Math.max(start, piece.submittedStart), hi = Math.min(end, piece.submittedStart + piece.original.length());
            if (hi > lo) result.add(new Range(piece.paragraphIndex, piece.start + lo - piece.submittedStart, piece.start + hi - piece.submittedStart));
        }
        return result;
    }
    public boolean unchanged(DocxDocument document) {
        for (Piece piece : pieces) {
            DocxDocument.ParagraphBlock paragraph = find(document, piece.paragraphIndex);
            if (paragraph == null || piece.end > paragraph.text.length() || !paragraph.text.substring(piece.start, piece.end).equals(piece.original)) return false;
        }
        return true;
    }
    public static DocxDocument.ParagraphBlock find(DocxDocument document, int index) {
        for (DocxDocument.ParagraphBlock paragraph : document.paragraphs) if (paragraph.index == index) return paragraph; return null;
    }
}

package com.rikkahub.wordlite;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Accepted-view offsets with explicit deleted text and old formatting, independent of Android. */
public final class ReviewManager {
    private ReviewManager() { }
    public static String now() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC")); return format.format(new Date());
    }
    public static int nextRevisionId(DocxDocument document) {
        int id = 0; for (DocxDocument.Revision revision : document.revisions) id = Math.max(id, revision.id + 1); return id;
    }
    public static int nextCommentId(DocxDocument document) {
        int id = 0; for (DocxDocument.Comment comment : document.comments) id = Math.max(id, comment.id + 1); return id;
    }
    public static DocxDocument.Comment comment(DocxDocument document, int paragraph, int start, int end, String text) {
        DocxDocument.Comment result = new DocxDocument.Comment(nextCommentId(document), paragraph, start, end, text);
        result.author = document.reviewAuthor; result.date = now(); result.paraId = paraId(result.id);
        document.comments.add(result); document.hasComments = true; return result;
    }
    public static DocxDocument.Comment reply(DocxDocument document, DocxDocument.Comment parent, String text) {
        DocxDocument.Comment result = comment(document, parent.paragraphIndex, parent.start, parent.end, text);
        result.parentId = parent.parentId >= 0 ? parent.parentId : parent.id; return result;
    }
    public static String paraId(int commentId) { return String.format(Locale.US, "%08X", 0x10000000L + commentId); }
    public static ArrayList<DocxDocument.Run> runs(DocxDocument.ParagraphBlock paragraph, int start, int end) {
        ArrayList<DocxDocument.Run> result = new ArrayList<DocxDocument.Run>(); int cursor = 0;
        for (DocxDocument.Run run : paragraph.runs) {
            int limit = cursor + run.text.length(), lo = Math.max(start, cursor), hi = Math.min(end, limit);
            if (hi > lo) result.add(new DocxDocument.Run(run.text.substring(lo - cursor, hi - cursor), run.style.copy()));
            cursor = limit;
        }
        return result;
    }
    private static DocxDocument.Revision revision(DocxDocument document, DocxDocument.ParagraphBlock paragraph,
                                                 DocxDocument.Revision.Kind kind, int start, int end) {
        DocxDocument.Revision revision = new DocxDocument.Revision();
        revision.id = nextRevisionId(document); revision.paragraphIndex = paragraph.index;
        revision.kind = kind; revision.start = start; revision.end = end;
        revision.author = document.reviewAuthor; revision.date = now();
        document.revisions.add(revision); document.hasTrackedChanges = true; return revision;
    }
    /** Called before an EditText mutates its characters. Existing ranges move exactly once. */
    public static void recordEdit(DocxDocument document, DocxDocument.ParagraphBlock paragraph,
                                  int start, int removed, String inserted) {
        int count = inserted.length(), limit = start + removed;
        if (start < 0 || removed < 0 || limit > paragraph.text.length()) throw new IllegalArgumentException("文字范围无效");
        ArrayList<DocxDocument.Revision> insertions = new ArrayList<DocxDocument.Revision>();
        for (DocxDocument.Revision existing : document.revisions)
            if (existing.paragraphIndex == paragraph.index && existing.kind == DocxDocument.Revision.Kind.INSERT
                    && existing.author.equals(document.reviewAuthor)) insertions.add(existing);
        ArrayList<DocxDocument.Run> deleted = new ArrayList<DocxDocument.Run>();
        int cursor = start;
        while (cursor < limit) {
            int next = limit; boolean pending = false;
            for (DocxDocument.Revision insertion : insertions) {
                if (cursor >= insertion.start && cursor < insertion.end) { pending = true; next = Math.min(next, insertion.end); }
                else if (insertion.start > cursor) next = Math.min(next, insertion.start);
            }
            if (!pending) deleted.addAll(runs(paragraph, cursor, next));
            cursor = next;
        }
        boolean mergedInsertion = false;
        for (int i = document.revisions.size() - 1; i >= 0; i--) {
            DocxDocument.Revision existing = document.revisions.get(i);
            if (existing.paragraphIndex != paragraph.index) continue;
            if (existing.kind == DocxDocument.Revision.Kind.INSERT) {
                int oldStart = existing.start, oldEnd = existing.end;
                boolean extend = document.trackRevisions && existing.author.equals(document.reviewAuthor)
                        && start >= oldStart && start <= oldEnd && limit <= oldEnd;
                // New untracked/other-author text at an edge must not inherit this insertion.
                existing.start = start == oldStart ? start + count
                        : EditOffsets.adjust(oldStart, start, removed, count, false);
                existing.end = start == oldEnd && removed == 0 ? oldEnd
                        : EditOffsets.adjust(oldEnd, start, removed, count, false);
                if (extend) { existing.start = oldStart; existing.end = oldEnd + count - removed; mergedInsertion = count > 0; }
                if (existing.end <= existing.start) document.revisions.remove(i);
            } else if (existing.kind == DocxDocument.Revision.Kind.DELETE) {
                existing.start = EditOffsets.adjust(existing.start, start, removed, count, false); existing.end = existing.start;
            } else {
                existing.start = EditOffsets.adjust(existing.start, start, removed, count, false);
                existing.end = EditOffsets.adjust(existing.end, start, removed, count, true);
            }
        }
        for (int i = document.displayHighlights.size() - 1; i >= 0; i--)
            if (document.displayHighlights.get(i).paragraphIndex == paragraph.index) document.displayHighlights.remove(i);
        for (DocxDocument.Comment comment : document.comments) if (comment.paragraphIndex == paragraph.index) {
            comment.start = EditOffsets.adjust(comment.start, start, removed, count, false);
            comment.end = EditOffsets.adjust(comment.end, start, removed, count, true);
        }
        for (DocxDocument.FieldCode field : paragraph.fields) if (field.start >= 0) {
            field.start = EditOffsets.adjust(field.start, start, removed, count, false);
            field.end = EditOffsets.adjust(field.end, start, removed, count, true);
        }
        for (DocxDocument.Hyperlink link : paragraph.hyperlinks) {
            link.start = EditOffsets.adjust(link.start, start, removed, count, false);
            link.end = EditOffsets.adjust(link.end, start, removed, count, true);
        }
        if (document.trackRevisions && !deleted.isEmpty()) {
            DocxDocument.Revision revision = revision(document, paragraph, DocxDocument.Revision.Kind.DELETE, start, start);
            StringBuilder value = new StringBuilder(); for (DocxDocument.Run run : deleted) value.append(run.text);
            revision.text = value.toString(); revision.previousRuns.addAll(deleted);
        }
        if (document.trackRevisions && count > 0 && !mergedInsertion)
            revision(document, paragraph, DocxDocument.Revision.Kind.INSERT, start, start + count);
        document.hasTrackedChanges = !document.revisions.isEmpty();
    }
    public static void format(DocxDocument document, DocxDocument.ParagraphBlock paragraph, int start, int end) {
        if (!document.trackRevisions || end <= start) return;
        for (DocxDocument.Revision existing : document.revisions)
            if (existing.paragraphIndex == paragraph.index && existing.kind == DocxDocument.Revision.Kind.FORMAT
                    && existing.start == start && existing.end == end && existing.author.equals(document.reviewAuthor)) return;
        DocxDocument.Revision revision = revision(document, paragraph, DocxDocument.Revision.Kind.FORMAT, start, end);
        revision.previousRuns.addAll(runs(paragraph, start, end));
    }
    public static void paragraphFormat(DocxDocument document, DocxDocument.ParagraphBlock paragraph) {
        if (!document.trackRevisions) return;
        for (DocxDocument.Revision r : document.revisions)
            if (r.paragraphIndex == paragraph.index && r.kind == DocxDocument.Revision.Kind.PARAGRAPH_FORMAT) return;
        DocxDocument.Revision r = revision(document, paragraph, DocxDocument.Revision.Kind.PARAGRAPH_FORMAT, 0, paragraph.text.length());
        r.previousParagraphFormat = paragraph.format.copy();
    }
    public static boolean decide(DocxDocument document, DocxDocument.Revision revision, boolean accept) {
        DocxDocument.ParagraphBlock paragraph = null;
        for (DocxDocument.ParagraphBlock p : document.paragraphs) if (p.index == revision.paragraphIndex) { paragraph = p; break; }
        if (paragraph == null || !document.revisions.remove(revision)) return false;
        if (!accept) {
            switch (revision.kind) {
                case INSERT: replace(document, paragraph, revision.start, revision.end, new ArrayList<DocxDocument.Run>()); break;
                case DELETE: replace(document, paragraph, revision.start, revision.start, revision.previousRuns); break;
                case FORMAT: replaceStyles(paragraph, revision.start, revision.end, revision.previousRuns); break;
                case PARAGRAPH_FORMAT:
                    if (revision.previousParagraphFormat != null) paragraph.format.set(revision.previousParagraphFormat); break;
            }
        }
        paragraph.textEdited = true; document.hasTrackedChanges = !document.revisions.isEmpty(); return true;
    }
    public static void replace(DocxDocument document, DocxDocument.ParagraphBlock paragraph, int start, int end,
                               ArrayList<DocxDocument.Run> replacement) {
        start = Math.max(0, Math.min(start, paragraph.text.length())); end = Math.max(start, Math.min(end, paragraph.text.length()));
        ArrayList<DocxDocument.Run> before = runs(paragraph, 0, start), after = runs(paragraph, end, paragraph.text.length());
        StringBuilder newText = new StringBuilder(); for (DocxDocument.Run run : replacement) newText.append(run.text);
        boolean tracking = document.trackRevisions; document.trackRevisions = false;
        try { recordEdit(document, paragraph, start, end - start, newText.toString()); }
        finally { document.trackRevisions = tracking; }
        paragraph.runs.clear(); paragraph.runs.addAll(before); paragraph.runs.addAll(replacement); paragraph.runs.addAll(after);
        paragraph.text = paragraph.text.substring(0, start) + newText + paragraph.text.substring(end); paragraph.textEdited = true;
    }
    public static void replaceStyles(DocxDocument.ParagraphBlock paragraph, int start, int end, ArrayList<DocxDocument.Run> previous) {
        ArrayList<DocxDocument.Run> before = runs(paragraph, 0, start), after = runs(paragraph, end, paragraph.text.length());
        paragraph.runs.clear(); paragraph.runs.addAll(before);
        String value = paragraph.text.substring(Math.min(start, paragraph.text.length()), Math.min(end, paragraph.text.length()));
        int at = 0;
        for (DocxDocument.Run old : previous) {
            int next = Math.min(value.length(), at + old.text.length());
            if (next > at) paragraph.runs.add(new DocxDocument.Run(value.substring(at, next), old.style.copy())); at = next;
        }
        if (at < value.length()) paragraph.runs.add(new DocxDocument.Run(value.substring(at), paragraph.baseRunStyle.copy()));
        paragraph.runs.addAll(after);
    }
}

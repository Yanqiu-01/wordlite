package com.rikkahub.wordlite;

/** Detached selection copy: exporting never mutates the editing document. */
public final class DocumentSlices {
    private DocumentSlices() { }
    public static DocxDocument selection(DocxDocument document, PdfExportOptions options) {
        DocxDocument.ParagraphBlock source = null;
        for (DocxDocument.ParagraphBlock p : document.paragraphs)
            if (p.index == options.paragraphIndex) { source = p; break; }
        options.validateSelection(source);
        int start = options.selectionStart, end = options.selectionEnd;
        DocxDocument copy = new DocxDocument();
        copy.fileName = document.fileName; copy.title = document.title;
        DocxDocument.SectionSettings section = document.sections.isEmpty() ? document.section
                : document.sections.get(Math.min(source.sectionIndex, document.sections.size() - 1));
        copy.section.set(section); copy.sections.add(section.copy());
        DocxDocument.ParagraphBlock paragraph = new DocxDocument.ParagraphBlock();
        paragraph.index = source.index; paragraph.text = source.text.substring(start, end);
        paragraph.baseRunStyle.merge(source.baseRunStyle); paragraph.format.set(source.format);
        paragraph.format.pageBreakBefore = false;
        paragraph.isHeading = source.isHeading;
        int cursor = 0;
        for (DocxDocument.Run run : source.runs) {
            int runEnd = cursor + run.text.length();
            int lo = Math.max(start, cursor), hi = Math.min(end, runEnd);
            if (hi > lo) paragraph.runs.add(new DocxDocument.Run(run.text.substring(lo - cursor, hi - cursor), run.style.copy()));
            cursor = runEnd;
        }
        if (paragraph.runs.isEmpty()) paragraph.runs.add(new DocxDocument.Run(paragraph.text, source.baseRunStyle.copy()));
        for (DocxDocument.Hyperlink link : source.hyperlinks) {
            int lo = Math.max(start, link.start), hi = Math.min(end, link.end);
            if (hi > lo) {
                DocxDocument.Hyperlink target = new DocxDocument.Hyperlink();
                target.start = lo - start; target.end = hi - start;
                target.target = link.target; target.relationshipId = link.relationshipId; target.anchor = link.anchor;
                paragraph.hyperlinks.add(target);
            }
        }
        for (DocxDocument.FieldCode field : source.fields) {
            if (field.start < start || field.end > end || field.start < 0) continue;
            DocxDocument.FieldCode target = new DocxDocument.FieldCode(field.instruction, field.cachedResult, field.simple);
            target.start = field.start - start; target.end = field.end - start; paragraph.fields.add(target);
        }
        if (options.includeComments) for (DocxDocument.Comment comment : document.comments) {
            if (comment.paragraphIndex != source.index || comment.end <= start || comment.start >= end) continue;
            DocxDocument.Comment target = new DocxDocument.Comment(comment.id, paragraph.index,
                    Math.max(start, comment.start) - start, Math.min(end, comment.end) - start, comment.text);
            target.author = comment.author; target.date = comment.date; target.resolved = comment.resolved;
            copy.comments.add(target);
        }
        if (options.includeRevisions) for (DocxDocument.Revision revision : document.revisions) {
            if (revision.paragraphIndex != source.index || revision.end < start || revision.start > end) continue;
            DocxDocument.Revision target = ReviewCopies.copyRevision(revision);
            target.start = Math.max(start, revision.start) - start; target.end = Math.min(end, Math.max(start, revision.end)) - start;
            copy.revisions.add(target);
        }
        copy.paragraphs.add(paragraph); copy.blocks.add(paragraph);
        return copy;
    }
}

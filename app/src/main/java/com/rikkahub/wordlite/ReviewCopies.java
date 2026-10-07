package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.HashMap;

/** Detached display model with revision markup, leaving editing text/offsets unchanged. */
public final class ReviewCopies {
    private ReviewCopies() { }
    public static DocxDocument marked(DocxDocument source) {
        DocxDocument target = new DocxDocument(); target.fileName = source.fileName; target.title = source.title;
        target.section.set(source.section);
        for (DocxDocument.SectionSettings section : source.sections) target.sections.add(section.copy());
        HashMap<Integer, DocxDocument.ParagraphBlock> paragraphs = new HashMap<Integer, DocxDocument.ParagraphBlock>();
        for (DocxDocument.ParagraphBlock p : source.paragraphs) {
            DocxDocument.ParagraphBlock copy = new DocxDocument.ParagraphBlock(); copy.index = p.index; copy.sectionIndex = p.sectionIndex;
            copy.text = p.text; copy.styleId = p.styleId; copy.styleName = p.styleName; copy.isHeading = p.isHeading;
            copy.isList = p.isList; copy.listLabel = p.listLabel; copy.baseRunStyle.merge(p.baseRunStyle); copy.format.set(p.format);
            for (DocxDocument.Run run : p.runs) copy.runs.add(new DocxDocument.Run(run.text, run.style.copy()));
            copy.images.addAll(p.images);
            for (DocxDocument.FieldCode field : p.fields) {
                DocxDocument.FieldCode cloned = new DocxDocument.FieldCode(field.instruction, field.cachedResult, field.simple);
                cloned.start = field.start; cloned.end = field.end; copy.fields.add(cloned);
            }
            for (DocxDocument.Hyperlink link : p.hyperlinks) {
                DocxDocument.Hyperlink cloned = new DocxDocument.Hyperlink();
                cloned.start = link.start; cloned.end = link.end; cloned.relationshipId = link.relationshipId;
                cloned.target = link.target; cloned.anchor = link.anchor; copy.hyperlinks.add(cloned);
            }
            paragraphs.put(p.index, copy); target.paragraphs.add(copy);
        }
        for (DocxDocument.Block block : source.blocks) {
            if (block instanceof DocxDocument.ParagraphBlock) target.blocks.add(paragraphs.get(block.index));
            else if (block instanceof DocxDocument.TableBlock) {
                DocxDocument.TableBlock table = new DocxDocument.TableBlock(); table.index = block.index; table.sectionIndex = block.sectionIndex;
                table.columns = ((DocxDocument.TableBlock) block).columns;
                for (ArrayList<DocxDocument.Cell> row : ((DocxDocument.TableBlock) block).rows) {
                    ArrayList<DocxDocument.Cell> cloned = new ArrayList<DocxDocument.Cell>();
                    for (DocxDocument.Cell cell : row) {
                        DocxDocument.Cell clonedCell = new DocxDocument.Cell();
                        for (DocxDocument.ParagraphBlock p : cell.paragraphs) clonedCell.paragraphs.add(paragraphs.get(p.index));
                        cloned.add(clonedCell);
                    }
                    table.rows.add(cloned);
                }
                target.blocks.add(table);
            }
        }
        for (DocxDocument.Comment comment : source.comments) target.comments.add(copyComment(comment));
        ArrayList<DocxDocument.Revision> revisions = new ArrayList<DocxDocument.Revision>();
        for (DocxDocument.Revision revision : source.revisions) revisions.add(copyRevision(revision));
        for (DocxDocument.Revision revision : revisions) {
            if (revision.kind == DocxDocument.Revision.Kind.DELETE) continue;
            DocxDocument.ParagraphBlock p = paragraphs.get(revision.paragraphIndex); if (p == null) continue;
            ArrayList<DocxDocument.Run> styles = ReviewManager.runs(p, revision.start, revision.end);
            for (DocxDocument.Run run : styles) {
                run.style.colorSet = true;
                run.style.color = revision.kind == DocxDocument.Revision.Kind.INSERT ? 0xFF18804A : 0xFF2865AD;
                run.style.underline = true; run.style.underlineSet = true;
            }
            ReviewManager.replaceStyles(p, revision.start, revision.end, styles);
        }
        java.util.Collections.sort(revisions, (a, b) -> Integer.compare(b.start, a.start));
        for (DocxDocument.Revision revision : revisions) {
            if (revision.kind != DocxDocument.Revision.Kind.DELETE) continue;
            DocxDocument.ParagraphBlock p = paragraphs.get(revision.paragraphIndex); if (p == null) continue;
            ArrayList<DocxDocument.Run> deleted = new ArrayList<DocxDocument.Run>();
            for (DocxDocument.Run run : revision.previousRuns) {
                DocxDocument.RunStyle style = run.style.copy(); style.strike = true; style.strikeSet = true;
                style.color = 0xFFB13232; style.colorSet = true; deleted.add(new DocxDocument.Run(run.text, style));
            }
            if (deleted.isEmpty()) {
                DocxDocument.RunStyle style = p.baseRunStyle.copy(); style.strike = true; style.strikeSet = true; style.color = 0xFFB13232; style.colorSet = true;
                deleted.add(new DocxDocument.Run(revision.text, style));
            }
            ReviewManager.replace(target, p, revision.start, revision.start, deleted);
        }
        return target;
    }
    public static DocxDocument.Revision copyRevision(DocxDocument.Revision source) {
        DocxDocument.Revision target = new DocxDocument.Revision();
        target.id = source.id; target.paragraphIndex = source.paragraphIndex; target.start = source.start; target.end = source.end;
        target.kind = source.kind; target.author = source.author; target.date = source.date; target.text = source.text;
        target.previousPropertiesXml = source.previousPropertiesXml;
        target.previousParagraphFormat = source.previousParagraphFormat == null ? null : source.previousParagraphFormat.copy();
        for (DocxDocument.Run run : source.previousRuns) target.previousRuns.add(new DocxDocument.Run(run.text, run.style.copy())); return target;
    }
    public static DocxDocument.Comment copyComment(DocxDocument.Comment source) {
        DocxDocument.Comment target = new DocxDocument.Comment(source.id, source.paragraphIndex, source.start, source.end, source.text);
        target.author = source.author; target.date = source.date; target.parentId = source.parentId; target.resolved = source.resolved; target.paraId = source.paraId;
        return target;
    }
}

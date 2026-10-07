package com.rikkahub.wordlite;

import java.util.ArrayList;

/** Table structure editing on the model layer (JVM-testable). */
public final class TableOps {
    private TableOps() { }

    public static final class Location {
        public final DocxDocument.TableBlock table;
        public final int row, col;
        Location(DocxDocument.TableBlock table, int row, int col) {
            this.table = table; this.row = row; this.col = col;
        }
    }

    /** Locates the table, row and column containing a paragraph index. */
    public static Location locate(DocxDocument document, int paragraphIndex) {
        if (document == null || paragraphIndex < 0) return null;
        for (DocxDocument.Block block : document.blocks) {
            if (!(block instanceof DocxDocument.TableBlock)) continue;
            DocxDocument.TableBlock table = (DocxDocument.TableBlock) block;
            for (int r = 0; r < table.rows.size(); r++) {
                ArrayList<DocxDocument.Cell> cells = table.rows.get(r);
                for (int c = 0; c < cells.size(); c++)
                    for (DocxDocument.ParagraphBlock p : cells.get(c).paragraphs)
                        if (p.index == paragraphIndex) return new Location(table, r, c);
            }
        }
        return null;
    }

    private static int nextIndex(DocxDocument document) {
        int next = 0;
        for (DocxDocument.Block block : document.blocks) next = Math.max(next, block.index + 1);
        return next;
    }

    /** ParagraphFormat is copied field by field so aliasing never happens. */
    private static void applyFormatTo(DocxDocument.ParagraphFormat out, DocxDocument.ParagraphFormat in) {
        out.alignment = in.alignment;
        out.leftIndentTwips = in.leftIndentTwips;
        out.rightIndentTwips = in.rightIndentTwips;
        out.firstLineIndentTwips = in.firstLineIndentTwips;
        out.spacingBeforeTwips = in.spacingBeforeTwips;
        out.spacingAfterTwips = in.spacingAfterTwips;
        out.spacingBeforeLines = in.spacingBeforeLines;
        out.spacingAfterLines = in.spacingAfterLines;
        out.lineSpacingTwips = in.lineSpacingTwips;
        out.lineRule = in.lineRule;
        out.pageBreakBefore = in.pageBreakBefore;
        out.pageBreakBeforeSet = in.pageBreakBeforeSet;
        out.keepNext = in.keepNext;
        out.keepNextSet = in.keepNextSet;
        out.keepLines = in.keepLines;
        out.keepLinesSet = in.keepLinesSet;
        out.widowControl = in.widowControl;
        out.widowControlSet = in.widowControlSet;
        out.numId = in.numId;
        out.ilvl = in.ilvl;
    }

    private static DocxDocument.ParagraphBlock copyParagraph(DocxDocument document, DocxDocument.ParagraphBlock p) {
        DocxDocument.ParagraphBlock copy = new DocxDocument.ParagraphBlock();
        copy.index = nextIndex(document);
        copy.text = "";
        applyFormatTo(copy.format, p.format);
        copy.baseRunStyle.merge(p.baseRunStyle);
        copy.styleId = p.styleId;
        copy.styleName = p.styleName;
        copy.isHeading = p.isHeading;
        copy.isList = p.isList;
        copy.listLabel = p.listLabel;
        copy.hasDirectFormatting = p.hasDirectFormatting;
        copy.runs.addAll(p.runs);
        document.paragraphs.add(copy);
        return copy;
    }

    private static DocxDocument.ParagraphBlock emptyParagraph(DocxDocument document) {
        DocxDocument.ParagraphBlock paragraph = new DocxDocument.ParagraphBlock();
        paragraph.index = nextIndex(document);
        paragraph.text = "";
        document.paragraphs.add(paragraph);
        return paragraph;
    }

    private static DocxDocument.Cell copyCell(DocxDocument document, DocxDocument.Cell source) {
        DocxDocument.Cell cell = new DocxDocument.Cell();
        cell.widthTwips = source.widthTwips;
        cell.widthType = source.widthType;
        cell.gridSpan = source.gridSpan;
        cell.verticalAlign = source.verticalAlign;
        cell.marginTopTwips = source.marginTopTwips;
        cell.marginLeftTwips = source.marginLeftTwips;
        cell.marginBottomTwips = source.marginBottomTwips;
        cell.marginRightTwips = source.marginRightTwips;
        for (DocxDocument.ParagraphBlock p : source.paragraphs)
            cell.paragraphs.add(copyParagraph(document, p));
        if (cell.paragraphs.isEmpty()) cell.paragraphs.add(emptyParagraph(document));
        return cell;
    }

    /** Inserts a row copying the format (and content shape) of the given row. */
    public static boolean insertRow(DocxDocument document, DocxDocument.TableBlock table, int row, boolean before) {
        if (document == null || table == null || table.rows.isEmpty()) return false;
        int at = Math.max(0, Math.min(row, table.rows.size() - 1));
        ArrayList<DocxDocument.Cell> cells = new ArrayList<DocxDocument.Cell>();
        for (DocxDocument.Cell cell : table.rows.get(at)) cells.add(copyCell(document, cell));
        int insertedAt = before ? at : Math.min(at + 1, table.rows.size());
        table.rows.add(insertedAt, cells);
        table.columns = Math.max(table.columns, cells.size());
        // A new row inherits its neighbour's w:trPr, the same way Word extends a table.
        if (!table.rowFormats.isEmpty()) {
            DocxDocument.RowFormat source = table.rowFormats.get(Math.min(at, table.rowFormats.size() - 1));
            DocxDocument.RowFormat copy = new DocxDocument.RowFormat();
            copy.heightTwips = source.heightTwips;
            copy.heightRule = source.heightRule;
            copy.cantSplit = source.cantSplit;
            copy.repeatAsHeader = source.repeatAsHeader;
            table.rowFormats.add(Math.min(insertedAt, table.rowFormats.size()), copy);
        }
        return true;
    }

    public static boolean deleteRow(DocxDocument document, DocxDocument.TableBlock table, int row) {
        if (document == null || table == null || table.rows.size() <= 1) return false;
        int at = Math.max(0, Math.min(row, table.rows.size() - 1));
        for (DocxDocument.Cell cell : table.rows.get(at))
            for (DocxDocument.ParagraphBlock p : cell.paragraphs) document.paragraphs.remove(p);
        table.rows.remove(at);
        if (at < table.rowFormats.size()) table.rowFormats.remove(at);
        return true;
    }

    /** Inserts a column copying the format of the given column in every row. */
    public static boolean insertColumn(DocxDocument document, DocxDocument.TableBlock table, int col, boolean before) {
        if (document == null || table == null || table.rows.isEmpty()) return false;
        boolean added = false;
        for (ArrayList<DocxDocument.Cell> row : table.rows) {
            int at = Math.max(0, Math.min(col, row.size()));
            DocxDocument.Cell source = row.get(Math.min(at, row.size() - 1));
            row.add(before ? at : Math.min(at + 1, row.size()), copyCell(document, source));
            added = true;
        }
        for (ArrayList<DocxDocument.Cell> row : table.rows)
            table.columns = Math.max(table.columns, row.size());
        // w:tblGrid is what fixes the column widths, so the new column needs a grid entry too.
        if (!table.gridColumns.isEmpty()) {
            int at = Math.max(0, Math.min(col, table.gridColumns.size() - 1));
            int insertAt = before ? at : Math.min(at + 1, table.gridColumns.size());
            table.gridColumns.add(insertAt,
                    Integer.valueOf(table.gridColumns.get(Math.min(at, table.gridColumns.size() - 1)).intValue()));
        }
        return added;
    }

    public static boolean deleteColumn(DocxDocument document, DocxDocument.TableBlock table, int col) {
        if (document == null || table == null || table.rows.isEmpty()) return false;
        int min = Integer.MAX_VALUE;
        for (ArrayList<DocxDocument.Cell> row : table.rows) min = Math.min(min, row.size());
        if (min <= 1) return false;
        for (ArrayList<DocxDocument.Cell> row : table.rows) {
            int at = Math.max(0, Math.min(col, row.size() - 1));
            DocxDocument.Cell cell = row.remove(at);
            for (DocxDocument.ParagraphBlock p : cell.paragraphs) document.paragraphs.remove(p);
        }
        table.columns = 1;
        for (ArrayList<DocxDocument.Cell> row : table.rows) table.columns = Math.max(table.columns, row.size());
        if (table.gridColumns.size() > 1)
            table.gridColumns.remove(Math.max(0, Math.min(col, table.gridColumns.size() - 1)));
        return true;
    }
}

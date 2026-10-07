package com.rikkahub.wordlite;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * Word reads a table from w:tblGrid plus per-cell w:tcW/w:tcMar and pins rows with w:trHeight.
 * These checks keep the parser, the paginator and the writer honest about all three.
 */
public final class TableGeometryRegression {
    private static int checks;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }

    private static void near(String message, float actual, float expected, float tolerance) {
        check(Math.abs(actual - expected) <= tolerance,
                message + " (expected " + expected + " got " + actual + ")");
    }

    private static ArrayList<DocxDocument.TableBlock> tablesOf(DocxDocument document) {
        ArrayList<DocxDocument.TableBlock> found = new ArrayList<DocxDocument.TableBlock>();
        for (DocxDocument.Block block : document.blocks)
            if (block instanceof DocxDocument.TableBlock) found.add((DocxDocument.TableBlock) block);
        return found;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("input.docx output.docx fixture.docx");
        File input = new File(args[0]);
        File output = new File(args[1]);
        File fixture = new File(args[2]);
        DocxDocument document = DocxParser.parse(new FileInputStream(input), input.getName());
        ArrayList<DocxDocument.TableBlock> tables = tablesOf(document);
        check(tables.size() == 4, "all four tables of the real document are readable");

        DocxDocument.TableBlock cover = tables.get(0);
        check(cover.gridColumns.size() == 2
                        && cover.gridColumns.get(0).intValue() == 1952
                        && cover.gridColumns.get(1).intValue() == 6354,
                "w:tblGrid column widths are read instead of an even split");
        check(cover.fixedLayout, "w:tblLayout type fixed is retained");
        check("center".equals(cover.alignment), "w:jc on the table itself is retained");
        check(cover.cellMarginLeftTwips == 108 && cover.cellMarginRightTwips == 108
                        && cover.cellMarginTopTwips == 0,
                "w:tblCellMar defaults are read");
        DocxDocument.RowFormat coverRow = cover.rowFormats.get(0);
        check(coverRow.heightTwips == 805 && "atLeast".equals(coverRow.heightRule),
                "w:trHeight with hRule atLeast is read");
        check(!coverRow.cantSplit, "a row without w:cantSplit is not marked unbreakable");
        DocxDocument.Cell label = cover.rows.get(0).get(0);
        check(label.marginTopTwips == 130 && label.marginLeftTwips == 60 && label.marginRightTwips == 60,
                "w:tcMar overrides the table default per cell");
        check("center".equals(label.verticalAlign), "w:vAlign is read");
        check(label.widthTwips == 1952 && "dxa".equals(label.widthType), "w:tcW dxa is read");

        DocxDocument.TableBlock pinned = tables.get(2);
        check(pinned.rows.size() == 12 && pinned.gridColumns.size() == 4,
                "the four column table is readable");
        boolean allCantSplit = true, allExact = true;
        for (DocxDocument.RowFormat format : pinned.rowFormats) {
            if (!format.cantSplit) allCantSplit = false;
            if (format.heightTwips != 454 || !"exact".equals(format.heightRule)) allExact = false;
        }
        check(allCantSplit, "every w:cantSplit row is marked");
        check(allExact, "every w:trHeight exact row is marked");
        check(pinned.rowFormats.size() == pinned.rows.size(), "row formats stay aligned with the row list");
        check(tables.get(3).widthTwips == 8574 && "dxa".equals(tables.get(3).widthType),
                "w:tblW in dxa is read");
        check(tables.get(3).rows.size() == 21, "the 21 row table is readable");

        // --- Roundtrip of the geometry Word needs to redraw the same table ---
        DocxWriter.write(new FileInputStream(input), new FileOutputStream(output), document);
        DocxDocument saved = DocxParser.parse(new FileInputStream(output), output.getName());
        ArrayList<DocxDocument.TableBlock> savedTables = tablesOf(saved);
        check(savedTables.size() == tables.size(), "table count survives a save");
        check(savedTables.get(2).gridColumns.equals(tables.get(2).gridColumns),
                "w:tblGrid survives a save roundtrip");
        check(savedTables.get(2).rowFormats.size() == 12, "row formats survive a save roundtrip");
        boolean stillPinned = true;
        for (DocxDocument.RowFormat format : savedTables.get(2).rowFormats)
            if (format.heightTwips != 454 || !"exact".equals(format.heightRule) || !format.cantSplit)
                stillPinned = false;
        check(stillPinned, "w:trHeight exact and w:cantSplit survive a save roundtrip");
        check("center".equals(savedTables.get(0).alignment) && savedTables.get(0).fixedLayout,
                "table w:jc and w:tblLayout survive a save roundtrip");
        DocxDocument.Cell savedLabel = savedTables.get(0).rows.get(0).get(0);
        check(savedLabel.marginTopTwips == 130 && savedLabel.marginLeftTwips == 60
                        && "center".equals(savedLabel.verticalAlign) && savedLabel.widthTwips == 1952,
                "w:tcMar, w:vAlign and w:tcW survive a save roundtrip");
        check(savedTables.get(3).widthTwips == 8574 && "dxa".equals(savedTables.get(3).widthType),
                "w:tblW survives a save roundtrip");

        // --- A table the app itself inserts gets a real grid, not tcW auto ---
        DocxDocument host = DocxParser.parse(new FileInputStream(fixture), fixture.getName());
        DocxDocument.TableBlock made = new DocxDocument.TableBlock();
        made.index = host.blocks.size() + 7;
        made.columns = 3;
        made.fixedLayout = true;
        made.alignment = "center";
        made.gridColumns.add(Integer.valueOf(1200));
        made.gridColumns.add(Integer.valueOf(2400));
        made.gridColumns.add(Integer.valueOf(3600));
        DocxDocument.RowFormat madeFormat = new DocxDocument.RowFormat();
        madeFormat.heightTwips = 300;
        madeFormat.heightRule = "exact";
        madeFormat.cantSplit = true;
        madeFormat.repeatAsHeader = true;
        made.rowFormats.add(madeFormat);
        ArrayList<DocxDocument.Cell> madeRow = new ArrayList<DocxDocument.Cell>();
        for (int i = 0; i < 3; i++) {
            DocxDocument.Cell cell = new DocxDocument.Cell();
            cell.widthTwips = 1200 * (i + 1);
            cell.widthType = "dxa";
            madeRow.add(cell);
        }
        made.rows.add(madeRow);
        host.blocks.add(made);
        File madeFile = new File(output.getParentFile(), "table-geometry-inserted.docx");
        DocxWriter.write(new FileInputStream(fixture), new FileOutputStream(madeFile), host);
        DocxDocument madeBack = DocxParser.parse(new FileInputStream(madeFile), madeFile.getName());
        DocxDocument.TableBlock found = null;
        for (DocxDocument.TableBlock table : tablesOf(madeBack))
            if (table.gridColumns.size() == 3 && table.gridColumns.get(2).intValue() == 3600) found = table;
        check(found != null, "an inserted table comes back with its w:tblGrid");
        if (found != null) {
            check(found.fixedLayout && "center".equals(found.alignment),
                    "an inserted table keeps w:tblLayout and w:jc");
            check(found.rowFormats.size() == 1 && found.rowFormats.get(0).cantSplit
                            && found.rowFormats.get(0).heightTwips == 300
                            && "exact".equals(found.rowFormats.get(0).heightRule)
                            && found.rowFormats.get(0).repeatAsHeader,
                    "an inserted table keeps w:trHeight, w:cantSplit and w:tblHeader");
            check(found.rows.get(0).get(1).widthTwips == 2400 && "dxa".equals(found.rows.get(0).get(1).widthType),
                    "an inserted table keeps per-cell w:tcW");
        }

        // --- Editing columns keeps the grid in step ---
        DocxDocument edited = DocxParser.parse(new FileInputStream(input), input.getName());
        ArrayList<DocxDocument.TableBlock> editedTables = tablesOf(edited);
        DocxDocument.TableBlock four = editedTables.get(2);
        int gridBefore = four.gridColumns.size();
        check(TableOps.insertColumn(edited, four, 1, true) && four.gridColumns.size() == gridBefore + 1,
                "inserting a column adds a w:tblGrid entry");
        check(TableOps.deleteColumn(edited, four, 1) && four.gridColumns.size() == gridBefore,
                "deleting a column removes that w:tblGrid entry");
        int rowFormatsBefore = four.rowFormats.size();
        check(TableOps.insertRow(edited, four, 0, false) && four.rowFormats.size() == rowFormatsBefore + 1
                        && four.rowFormats.get(1).heightTwips == 454,
                "inserting a row copies the neighbouring w:trPr");
        check(TableOps.deleteRow(edited, four, 1) && four.rowFormats.size() == rowFormatsBefore
                        && four.rowFormats.size() == four.rows.size(),
                "deleting a row keeps w:trPr aligned with the rows");

        System.out.println("SUMMARY " + checks + " table geometry assertions passed");
    }
}

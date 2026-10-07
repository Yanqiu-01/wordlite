package com.rikkahub.wordlite;

import android.text.StaticLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Platform-shaped paragraphs + deterministic page breaking. */
public final class A4Paginator {
    public static final int A4_WIDTH_TWIPS = 11906, A4_HEIGHT_TWIPS = 16838, DEFAULT_MARGIN_TWIPS = 1440;
    public static final int MAX_FIELD_PASSES = 4;

    public static final class PageResult {
        public final List<PageContent> pages = new ArrayList<PageContent>();
        /** Kept for callers that need the document's final-section geometry. */
        public final PageGeometry geometry;
        public int imageFragments;
        PageResult(PageGeometry geometry) { this.geometry = geometry; }
        public int totalPages() { return pages.size(); }
    }

    public static final class PageContent {
        public final List<ParagraphLayout> paragraphs = new ArrayList<ParagraphLayout>();
        /** Geometry and numbering belonging to this physical page's section. */
        public PageGeometry geometry;
        public int sectionIndex;
        public int pageNumber;
        public int displayedPageNumber;
        public String pageNumberFormat = "decimal";
        public String headerText = "";
        public String footerTemplate = "";
        public String headerFontFamily = "";
        public String footerFontFamily = "";
        public float headerFontSizePt = 9f;
        public float footerFontSizePt = 10.5f;
        public int headerAlignment = 1;
        public int footerAlignment = 1;
        public boolean headerHasBottomBorder;
        public int headerBorderSizeEighthPt = 6;
        public int headerBorderSpacePt;
        public String headerBorderValue = "double";
        public boolean hasHeaderPart;
        public boolean hasFooterPart;
        public float usedHeight;
        public boolean overflow;
    }

    private static final class SectionBatch {
        final int sectionIndex;
        final PageGeometry geometry;
        final List<PageBreaker.Item> items = new ArrayList<PageBreaker.Item>();
        SectionBatch(int sectionIndex, PageGeometry geometry) {
            this.sectionIndex = sectionIndex;
            this.geometry = geometry;
        }
    }

    public static final class ParagraphLayout {
        public int blockIndex, sectionIndex, startLine, endLine, startChar, endChar, lineCount;
        public float top, height, layoutWidth;
        public DocxTextLayout.Paragraph text;
        public DocxDocument.EmbeddedImage image;
        public TableRow row;
        public float imageWidth;
        /** Actual bitmap height; may be smaller than the paragraph's line box. */
        public float imageHeight;
    }

    public static final class CellParagraph {
        public DocxTextLayout.Paragraph text;
        public float x, y;
    }
    public static final class CellImage {
        public DocxDocument.EmbeddedImage image;
        public float x, y, width, height;
    }
    public static final class TableRow {
        public int columns;
        public float cellWidth, height;
        public final List<CellParagraph> paragraphs = new ArrayList<CellParagraph>();
        public final List<CellImage> images = new ArrayList<CellImage>();
    }

    private final PageGeometry geometry;
    public A4Paginator(DocxDocument.SectionSettings section) { geometry = new PageGeometry(section); }
    /** Density intentionally does not affect document layout. */
    public A4Paginator(DocxDocument.SectionSettings section, float ignoredDensity) { this(section); }

    /**
     * Fields can change line widths. Rebuild until the first-page mapping is stable,
     * while keeping each section's own paper size, margins and content width.
     */
    public PageResult paginate(DocxDocument document) {
        HashMap<DocxDocument.ParagraphBlock, ParagraphLayout> cache =
                new HashMap<DocxDocument.ParagraphBlock, ParagraphLayout>();
        Map<Integer, Integer> pageOf = null;
        int total = 0;
        PageResult result = measureAndBreak(document, null, 0, cache);
        for (int pass = 1; pass < MAX_FIELD_PASSES; pass++) {
            pageOf = firstPagePerBlock(result);
            total = result.totalPages();
            PageResult next = measureAndBreak(document, pageOf, total, cache);
            Map<Integer, Integer> nextPageOf = firstPagePerBlock(next);
            boolean stable = next.totalPages() == total && nextPageOf.equals(pageOf);
            result = next;
            if (stable) break;
        }
        return result;
    }

    /** Lowest displayed page number on which each block's first fragment appears. */
    private static Map<Integer, Integer> firstPagePerBlock(PageResult result) {
        Map<Integer, Integer> map = new HashMap<Integer, Integer>();
        for (PageContent page : result.pages) {
            int number = page.displayedPageNumber > 0 ? page.displayedPageNumber : page.pageNumber;
            for (ParagraphLayout layout : page.paragraphs)
                if (!map.containsKey(layout.blockIndex)) map.put(layout.blockIndex, number);
        }
        return map;
    }

    private static int effectiveSpacingTwips(DocxDocument.ParagraphBlock paragraph,
                                              boolean before, int lineGridPitchTwips) {
        if (paragraph == null || paragraph.format == null) return 0;
        int lines = before ? paragraph.format.spacingBeforeLines : paragraph.format.spacingAfterLines;
        if (lines >= 0) {
            // beforeLines/afterLines take precedence over before/after in Word.
            // One line is the section grid pitch when present; otherwise use
            // the OOXML one-line unit (240 twips).
            int unit = lineGridPitchTwips > 0 ? lineGridPitchTwips : 240;
            return Math.max(0, Math.round(unit * lines / 100f));
        }
        int twips = before ? paragraph.format.spacingBeforeTwips : paragraph.format.spacingAfterTwips;
        return Math.max(0, twips);
    }

    private static int sectionGrid(DocxDocument document, int sectionIndex) {
        return sectionOf(document, sectionIndex).lineGridPitchTwips;
    }

    private PageResult measureAndBreak(DocxDocument document,
                                       Map<Integer, Integer> pageOf, int totalPages,
                                       HashMap<DocxDocument.ParagraphBlock, ParagraphLayout> cache) {
        ArrayList<SectionBatch> batches = new ArrayList<SectionBatch>();
        IdentityHashMap<PageBreaker.Item, ParagraphLayout> measured =
                new IdentityHashMap<PageBreaker.Item, ParagraphLayout>();

        for (DocxDocument.Block block : document.blocks) {
            int sectionIndex = sectionIndex(block, document);
            SectionBatch batch = findOrCreateBatch(batches, sectionIndex, document);
            PageGeometry blockGeometry = batch.geometry;

            if (block instanceof DocxDocument.ParagraphBlock) {
                DocxDocument.ParagraphBlock p = (DocxDocument.ParagraphBlock) block;
                DocxFieldEngine.DisplayMap display = null;
                if (pageOf != null && !p.fields.isEmpty()) {
                    int page = pageOf.containsKey(p.index) ? pageOf.get(p.index) : 1;
                    display = DocxFieldEngine.displayTextWithMap(document, p, page, totalPages);
                    if (!display.changed) display = null;
                }
                ParagraphLayout layout = display == null ? cache.get(p) : null;
                if (layout == null || Math.abs(layout.layoutWidth - blockGeometry.contentWidth) > 0.01f) {
                    layout = new ParagraphLayout();
                    layout.blockIndex = p.index;
                    layout.sectionIndex = sectionIndex;
                    layout.layoutWidth = blockGeometry.contentWidth;
                    layout.text = DocxTextLayout.measure(p, blockGeometry.contentWidth, display,
                            batch.sectionIndex >= 0 ? sectionOf(document, batch.sectionIndex).lineGridPitchTwips : -1);
                    cache.put(p, layout);
                }
                StaticLayout l = layout.text.layout;
                boolean textPresent = p.text != null && p.text.length() > 0;
                boolean imageOnly = !textPresent && !p.images.isEmpty();
                if (!imageOnly) {
                    float[] heights = new float[l.getLineCount()];
                    boolean tocGrid = p.format.rightTabTwips > 0
                            && p.format.tabLeader != null
                            && p.format.tabLeader.length() > 0
                            && "auto".equalsIgnoreCase(p.format.lineRule)
                            && sectionGrid(document, sectionIndex) > 0;
                    boolean gridAdvance = (tocGrid || p.format.snapToGrid)
                            && sectionGrid(document, sectionIndex) > 0;
                    for (int i = 0; i < heights.length; i++) {
                        heights[i] = Math.max(1, l.getLineTop(i + 1) - l.getLineTop(i));
                        // StaticLayout rounds each line to an integer. Word's
                        // generated TOC and body docGrid rows retain the
                        // fractional grid remainder during pagination and
                        // drawing; otherwise the first body page fits too much.
                        if (gridAdvance) heights[i] += 0.5f;
                    }
                    if (heights.length == 0)
                        heights = new float[]{Math.max(1, PageGeometry.points(DocxTextLayout.defaultFontSizePoints(p)))};
                    PageBreaker.Item item = new PageBreaker.Item(p.index, heights);
                    int gridPitch = sectionGrid(document, sectionIndex);
                    item.before = PageGeometry.twips(effectiveSpacingTwips(p, true, gridPitch));
                    item.after = p.images.isEmpty()
                            ? PageGeometry.twips(effectiveSpacingTwips(p, false, gridPitch)) : 0;
                    item.pageBreakBefore = p.format.pageBreakBefore;
                    item.keepLines = p.format.keepLines;
                    item.keepNext = p.format.keepNext;
                    item.widowControl = p.format.widowControl;
                    item.sectionIndex = sectionIndex;
                    batch.items.add(item);
                    measured.put(item, layout);
                }
                for (int imageIndex = 0; imageIndex < p.images.size(); imageIndex++) {
                    DocxDocument.EmbeddedImage image = p.images.get(imageIndex);
                    ParagraphLayout graphic = new ParagraphLayout();
                    graphic.blockIndex = p.index;
                    graphic.sectionIndex = sectionIndex;
                    graphic.image = image;
                    float w = image.widthEmu > 0 ? image.widthEmu / 9525f : 192;
                    float h = image.heightEmu > 0 ? image.heightEmu / 9525f : 144;
                    float scale = Math.min(1, Math.min(blockGeometry.contentWidth / w, blockGeometry.contentHeight / h));
                    graphic.imageWidth = w * scale;
                    graphic.imageHeight = h * scale;
                    int gridPitch = sectionGrid(document, sectionIndex);
                    // An inline picture participates in its paragraph's line box.
                    // Empty-picture paragraphs used to bypass StaticLayout entirely,
                    // so a small inserted image consumed almost no document rows.
                    float imageLineBox = Math.max(graphic.imageHeight,
                            DocxTextLayout.minimumLineHeight(p, gridPitch));
                    boolean gridControlsImage = p.format.snapToGrid && gridPitch > 0
                            && graphic.imageHeight < DocxTextLayout.minimumLineHeight(p, gridPitch);
                    if (gridControlsImage) imageLineBox += 0.5f;
                    PageBreaker.Item picture = new PageBreaker.Item(p.index, Math.max(1, imageLineBox));
                    picture.before = textPresent || imageIndex > 0 ? 0
                            : PageGeometry.twips(effectiveSpacingTwips(p, true, gridPitch));
                    // Paragraph spacing belongs to the paragraph, not to every
                    // image run inside it. Apply it only after the final image.
                    picture.after = imageIndex == p.images.size() - 1
                            ? PageGeometry.twips(effectiveSpacingTwips(p, false, gridPitch)) : 0;
                    picture.pageBreakBefore = !textPresent && imageIndex == 0 && p.format.pageBreakBefore;
                    picture.widowControl = false;
                    picture.keepLines = true;
                    picture.sectionIndex = sectionIndex;
                    batch.items.add(picture);
                    measured.put(picture, graphic);
                }
            } else if (block instanceof DocxDocument.TableBlock) {
                DocxDocument.TableBlock table = (DocxDocument.TableBlock) block;
                for (ArrayList<DocxDocument.Cell> cells : table.rows) {
                    TableRow row = new TableRow();
                    row.columns = Math.max(1, Math.max(cells.size(), table.columns));
                    row.cellWidth = blockGeometry.contentWidth / row.columns;
                    row.height = 8;
                    for (int c = 0; c < cells.size(); c++) {
                        float y = 4;
                        for (DocxDocument.ParagraphBlock p : cells.get(c).paragraphs) {
                            int cellGrid = sectionGrid(document, sectionIndex);
                            y += PageGeometry.twips(effectiveSpacingTwips(p, true, cellGrid));
                            CellParagraph cp = new CellParagraph();
                            cp.text = DocxTextLayout.measure(p, Math.max(1, row.cellWidth - 8), null,
                                    batch.sectionIndex >= 0 ? sectionOf(document, batch.sectionIndex).lineGridPitchTwips : -1);
                            cp.x = c * row.cellWidth + 4; cp.y = y;
                            row.paragraphs.add(cp);
                            y += cp.text.layout.getHeight() + PageGeometry.twips(effectiveSpacingTwips(p, false, cellGrid));
                            for (DocxDocument.EmbeddedImage image : p.images) {
                                float iw = image.widthEmu > 0 ? image.widthEmu / 9525f : row.cellWidth - 8;
                                float ih = image.heightEmu > 0 ? image.heightEmu / 9525f : 144;
                                float imageScale = Math.min(1f, Math.min((row.cellWidth - 8) / Math.max(1f, iw), blockGeometry.contentHeight / Math.max(1f, ih)));
                                CellImage ci = new CellImage();
                                ci.image = image; ci.x = c * row.cellWidth + 4; ci.y = y;
                                ci.width = iw * imageScale; ci.height = ih * imageScale;
                                row.images.add(ci);
                                y += ci.height + 4;
                            }
                        }
                        row.height = Math.max(row.height, y + 4);
                    }
                    ParagraphLayout layout = new ParagraphLayout();
                    layout.blockIndex = table.index;
                    layout.sectionIndex = sectionIndex;
                    layout.row = row;
                    PageBreaker.Item item = new PageBreaker.Item(table.index, row.height);
                    item.widowControl = false; item.keepLines = true;
                    item.sectionIndex = sectionIndex;
                    batch.items.add(item); measured.put(item, layout);
                }
            }
        }

        PageResult result = new PageResult(geometry);
        for (SectionBatch batch : batches) {
            List<PageBreaker.Page> broken = PageBreaker.paginate(batch.items, batch.geometry.contentHeight);
            DocxDocument.SectionSettings section = sectionOf(document, batch.sectionIndex);
            for (PageBreaker.Page page : broken) {
                PageContent content = new PageContent();
                content.geometry = batch.geometry;
                content.sectionIndex = batch.sectionIndex;
                content.headerText = section.headerText;
                content.footerTemplate = section.footerTemplate;
                content.headerFontFamily = section.headerFontFamily;
                content.footerFontFamily = section.footerFontFamily;
                content.headerFontSizePt = section.headerFontSizePt;
                content.footerFontSizePt = section.footerFontSizePt;
                content.headerAlignment = section.headerAlignment;
                content.footerAlignment = section.footerAlignment;
                content.headerHasBottomBorder = section.headerHasBottomBorder;
                content.headerBorderSizeEighthPt = section.headerBorderSizeEighthPt;
                content.headerBorderSpacePt = section.headerBorderSpacePt;
                content.headerBorderValue = section.headerBorderValue;
                content.hasHeaderPart = section.headerPart != null && section.headerPart.length() > 0;
                content.hasFooterPart = section.footerPart != null && section.footerPart.length() > 0;
                content.pageNumberFormat = section.pageNumberFormat;
                content.usedHeight = page.usedHeight;
                content.overflow = page.overflow;
                for (PageBreaker.Fragment f : page.fragments) {
                    ParagraphLayout source = measured.get(f.item);
                    if (source == null) continue;
                    ParagraphLayout fragment = new ParagraphLayout();
                    fragment.blockIndex = source.blockIndex;
                    fragment.sectionIndex = source.sectionIndex;
                    fragment.layoutWidth = source.layoutWidth;
                    fragment.text = source.text; fragment.image = source.image;
                    if (fragment.image != null) result.imageFragments++;
                    fragment.imageWidth = source.imageWidth;
                    fragment.imageHeight = source.imageHeight;
                    fragment.row = source.row;
                    fragment.top = f.top; fragment.height = f.height;
                    fragment.startLine = f.startLine; fragment.endLine = f.endLine;
                    fragment.lineCount = f.endLine - f.startLine;
                    if (fragment.text != null && f.endLine > f.startLine) {
                        fragment.startChar = fragment.text.layout.getLineStart(f.startLine);
                        fragment.endChar = fragment.text.layout.getLineEnd(f.endLine - 1);
                    }
                    content.paragraphs.add(fragment);
                }
                result.pages.add(content);
            }
        }
        assignPageNumbers(result, document);
        return result;
    }

    private static int sectionIndex(DocxDocument.Block block, DocxDocument document) {
        if (document.sections.isEmpty()) return 0;
        return Math.max(0, Math.min(block.sectionIndex, document.sections.size() - 1));
    }

    private static SectionBatch findOrCreateBatch(ArrayList<SectionBatch> batches, int sectionIndex,
                                                   DocxDocument document) {
        for (SectionBatch batch : batches) if (batch.sectionIndex == sectionIndex) return batch;
        PageGeometry pageGeometry = new PageGeometry(sectionOf(document, sectionIndex));
        SectionBatch batch = new SectionBatch(sectionIndex, pageGeometry);
        batches.add(batch);
        return batch;
    }

    private static DocxDocument.SectionSettings sectionOf(DocxDocument document, int sectionIndex) {
        if (document.sections.isEmpty()) return document.section;
        return document.sections.get(Math.max(0, Math.min(sectionIndex, document.sections.size() - 1)));
    }

    private static void assignPageNumbers(PageResult result, DocxDocument document) {
        int physical = 0;
        int lastSection = -1;
        int local = 0;
        for (PageContent page : result.pages) {
            physical++;
            if (page.sectionIndex != lastSection) {
                lastSection = page.sectionIndex;
                local = 0;
            }
            local++;
            DocxDocument.SectionSettings section = sectionOf(document, page.sectionIndex);
            page.pageNumber = physical;
            page.displayedPageNumber = Math.max(1, section.pageNumberStart) + local - 1;
        }
    }

    public float contentWidth() { return geometry.contentWidth; }
    public float contentHeight() { return geometry.contentHeight; }
    public float totalPageWidthPx(DocxDocument.SectionSettings ignored) { return geometry.width; }
    public float totalPageHeightPx(DocxDocument.SectionSettings ignored) { return geometry.height; }
}

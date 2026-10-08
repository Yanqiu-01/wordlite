package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.List;

/** A small, dependency-free document model used by the reader and checker. */
public final class DocxDocument {
    public String fileName = "";
    public String title = "";
    public String originalRootAttributes = "";
    public String originalFinalSection = "";
    public String originalFinalSectionSignature = "";
    public final ArrayList<Block> blocks = new ArrayList<Block>();
    public final ArrayList<ParagraphBlock> paragraphs = new ArrayList<ParagraphBlock>();
    public final ArrayList<FormatIssue> issues = new ArrayList<FormatIssue>();
    public final ArrayList<Comment> comments = new ArrayList<Comment>();
    public final ArrayList<Revision> revisions = new ArrayList<Revision>();
    /** Transient view overlays; intentionally excluded from OOXML serialization. */
    public final ArrayList<DisplayHighlight> displayHighlights = new ArrayList<DisplayHighlight>();
    public boolean trackRevisions;
    public String reviewAuthor = "作者";

    /** All section definitions in document order. The legacy field points to the final section. */
    public final ArrayList<SectionSettings> sections = new ArrayList<SectionSettings>();
    public final SectionSettings section = new SectionSettings();
    public boolean hasTrackedChanges;
    public boolean hasMacros;
    public boolean hasHeaderFooter;
    public boolean hasFootnotes;
    public boolean hasEndnotes;
    public boolean hasComments;
    public boolean hasUnsupportedDrawing;
    public int imageCount;
    public int tableCount;

    public abstract static class Block {
        public int index = -1;
        /** Section containing this body block; assigned after OOXML parsing. */
        public int sectionIndex;
        public String originalXml = "";
        public String originalSignature = "";
    }

    public static final class OpaqueBlock extends Block { }

    public static final class ParagraphBlock extends Block {
        public final ArrayList<Run> runs = new ArrayList<Run>();
        public final ArrayList<EmbeddedImage> images = new ArrayList<EmbeddedImage>();
        public final ArrayList<FieldCode> fields = new ArrayList<FieldCode>();
        public final ArrayList<Hyperlink> hyperlinks = new ArrayList<Hyperlink>();
        public final StringBuilder editedText = new StringBuilder();
        public final ArrayList<RangeStyle> editedStyles = new ArrayList<RangeStyle>();
        public boolean textEdited;
        public final ParagraphFormat format = new ParagraphFormat();
        /** Inherited paragraph/style run defaults before direct per-run overrides. */
        public final RunStyle baseRunStyle = new RunStyle();
        public String styleId = "";
        public String styleName = "";
        public String text = "";
        public boolean isHeading;
        public boolean isList;
        public String listLabel = "";
        public boolean hasDirectFormatting;

        public boolean isEmpty() {
            return text.trim().length() == 0 && images.isEmpty();
        }

        public String firstFont() {
            for (Run run : runs) {
                if (run.text.trim().length() > 0 && run.style.fontFamily != null
                        && run.style.fontFamily.length() > 0) {
                    return run.style.fontFamily;
                }
            }
            return "";
        }

        public int firstSizeHalfPoints() {
            for (Run run : runs) {
                if (run.text.trim().length() > 0 && run.style.fontSizeHalfPoints > 0) {
                    return run.style.fontSizeHalfPoints;
                }
            }
            return -1;
        }
    }

    public static final class TableBlock extends Block {
        public final ArrayList<ArrayList<Cell>> rows = new ArrayList<ArrayList<Cell>>();
        public int columns;
        /** w:tblGrid/w:gridCol/@w:w in twips; empty when the document omits the grid. */
        public final ArrayList<Integer> gridColumns = new ArrayList<Integer>();
        /** w:tblW: @w:w plus @w:type (auto / dxa / pct). */
        public int widthTwips;
        public String widthType = "auto";
        /** w:tblInd, in twips. */
        public int indentTwips;
        /** w:tblLayout type="fixed" keeps the declared widths instead of fitting contents. */
        public boolean fixedLayout;
        /** w:tblCellMar defaults; Word ships 108 twips of left/right padding. */
        public int cellMarginTopTwips;
        public int cellMarginLeftTwips = 108;
        public int cellMarginBottomTwips;
        public int cellMarginRightTwips = 108;
        /** w:jc on the table itself: left / center / right. */
        public String alignment = "left";
        public final ArrayList<RowFormat> rowFormats = new ArrayList<RowFormat>();
    }

    /** The w:trPr facts that a plain row of cells cannot carry. */
    public static final class RowFormat {
        /** w:trHeight/@w:val in twips, 0 when unset. */
        public int heightTwips;
        /** w:trHeight/@w:hRule: atLeast (default) / exact / auto. */
        public String heightRule = "atLeast";
        /** w:cantSplit: the row must never break across pages. */
        public boolean cantSplit;
        /** w:tblHeader: Word repeats this row at the top of every page the table continues on. */
        public boolean repeatAsHeader;
    }

    public static final class Cell {
        public final ArrayList<ParagraphBlock> paragraphs = new ArrayList<ParagraphBlock>();
        /** w:tcW: @w:w plus @w:type. */
        public int widthTwips;
        public String widthType = "auto";
        /** w:gridSpan: how many w:tblGrid columns this cell covers. */
        public int gridSpan = 1;
        /** w:vMerge without @w:val="restart" continues the cell above, so it holds no content. */
        public boolean mergeContinues;
        /** w:vAlign: top (default) / center / bottom. */
        public String verticalAlign;
        /** w:tcMar in twips, -1 inherits the table default. */
        public int marginTopTwips = -1, marginLeftTwips = -1, marginBottomTwips = -1, marginRightTwips = -1;
    }

    public static final class Run {
        public String text;
        public final RunStyle style;

        public Run(String text, RunStyle style) {
            this.text = text == null ? "" : text;
            this.style = style == null ? new RunStyle() : style;
        }
    }

    public static final class RunStyle {
        /** Explicit generic/user-selected family plus OOXML script-specific font names. */
        public String fontFamily;
        public String asciiFontFamily;
        public String highAnsiFontFamily;
        public String eastAsiaFontFamily;
        public String complexScriptFontFamily;
        public int fontSizeHalfPoints = -1;
        public int complexScriptSizeHalfPoints = -1;
        /** OOXML w:position value in half-points; positive raises, negative lowers. */
        public int positionHalfPoints;
        public boolean positionSet;
        public int color = 0xFF222222;
        public boolean colorSet;
        public boolean bold;
        public boolean boldSet;
        public boolean italic;
        public boolean italicSet;
        public boolean underline;
        public boolean underlineSet;
        public boolean strike;
        public boolean strikeSet;
        public boolean superscript;
        public boolean superscriptSet;
        public boolean subscript;
        public boolean subscriptSet;
        public int highlight = -1;

        public RunStyle copy() {
            RunStyle out = new RunStyle();
            out.fontFamily = fontFamily;
            out.asciiFontFamily = asciiFontFamily;
            out.highAnsiFontFamily = highAnsiFontFamily;
            out.eastAsiaFontFamily = eastAsiaFontFamily;
            out.complexScriptFontFamily = complexScriptFontFamily;
            out.fontSizeHalfPoints = fontSizeHalfPoints;
            out.complexScriptSizeHalfPoints = complexScriptSizeHalfPoints;
            out.positionHalfPoints = positionHalfPoints;
            out.positionSet = positionSet;
            out.color = color;
            out.colorSet = colorSet;
            out.bold = bold;
            out.boldSet = boldSet;
            out.italic = italic;
            out.italicSet = italicSet;
            out.underline = underline;
            out.underlineSet = underlineSet;
            out.strike = strike;
            out.strikeSet = strikeSet;
            out.superscript = superscript;
            out.superscriptSet = superscriptSet;
            out.subscript = subscript;
            out.subscriptSet = subscriptSet;
            out.highlight = highlight;
            return out;
        }

        public void merge(RunStyle overlay) {
            if (overlay == null) return;
            if (overlay.fontFamily != null) fontFamily = overlay.fontFamily;
            if (overlay.asciiFontFamily != null) asciiFontFamily = overlay.asciiFontFamily;
            if (overlay.highAnsiFontFamily != null) highAnsiFontFamily = overlay.highAnsiFontFamily;
            if (overlay.eastAsiaFontFamily != null) eastAsiaFontFamily = overlay.eastAsiaFontFamily;
            if (overlay.complexScriptFontFamily != null) complexScriptFontFamily = overlay.complexScriptFontFamily;
            if (overlay.fontSizeHalfPoints > 0) fontSizeHalfPoints = overlay.fontSizeHalfPoints;
            if (overlay.complexScriptSizeHalfPoints > 0) complexScriptSizeHalfPoints = overlay.complexScriptSizeHalfPoints;
            if (overlay.positionSet) { positionHalfPoints = overlay.positionHalfPoints; positionSet = true; }
            if (overlay.colorSet) { color = overlay.color; colorSet = true; }
            if (overlay.boldSet) { bold = overlay.bold; boldSet = true; }
            if (overlay.italicSet) { italic = overlay.italic; italicSet = true; }
            if (overlay.underlineSet) { underline = overlay.underline; underlineSet = true; }
            if (overlay.strikeSet) { strike = overlay.strike; strikeSet = true; }
            if (overlay.superscriptSet) { superscript = overlay.superscript; superscriptSet = true; }
            if (overlay.subscriptSet) { subscript = overlay.subscript; subscriptSet = true; }
            if (overlay.highlight >= 0) highlight = overlay.highlight;
        }
    }

    public static final class ParagraphFormat {
        public int alignment = -1; // 0 left, 1 center, 2 right, 3 justify
        public int leftIndentTwips = -1;
        public int rightIndentTwips = -1;
        public int firstLineIndentTwips = -1;
        public int spacingBeforeTwips = -1;
        public int spacingAfterTwips = -1;
        /** OOXML beforeLines/afterLines are 1/100 of the paragraph line grid. */
        public int spacingBeforeLines = -1;
        public int spacingAfterLines = -1;
        public int lineSpacingTwips = -1;
        public String lineRule = "";
        public boolean snapToGrid;
        public boolean snapToGridSet;
        public boolean pageBreakBefore;
        public boolean pageBreakBeforeSet;
        public boolean keepNext;
        public boolean keepNextSet;
        public boolean keepLines;
        public boolean keepLinesSet;
        public boolean widowControl = true;
        public boolean widowControlSet;
        public int numId = -1;
        public int ilvl;
        /** Right-aligned tab position in twips; -1 means no tab stop was declared. */
        public int rightTabTwips = -1;
        public String tabLeader = "";
        /** Word autoSpaceDE / autoSpaceDN; absent means not enabled. */
        public boolean autoSpaceDe;
        public boolean autoSpaceDeSet;
        public boolean autoSpaceDn;
        public boolean autoSpaceDnSet;
        public boolean wordWrap;
        public boolean wordWrapSet;
        public boolean overflowPunct;
        public boolean overflowPunctSet;

        public ParagraphFormat copy() {
            ParagraphFormat out = new ParagraphFormat();
            out.alignment = alignment;
            out.leftIndentTwips = leftIndentTwips;
            out.rightIndentTwips = rightIndentTwips;
            out.firstLineIndentTwips = firstLineIndentTwips;
            out.spacingBeforeTwips = spacingBeforeTwips;
            out.spacingAfterTwips = spacingAfterTwips;
            out.spacingBeforeLines = spacingBeforeLines;
            out.spacingAfterLines = spacingAfterLines;
            out.lineSpacingTwips = lineSpacingTwips;
            out.lineRule = lineRule;
            out.snapToGrid = snapToGrid;
            out.snapToGridSet = snapToGridSet;
            out.pageBreakBefore = pageBreakBefore;
            out.pageBreakBeforeSet = pageBreakBeforeSet;
            out.keepNext = keepNext;
            out.keepNextSet = keepNextSet;
            out.keepLines = keepLines;
            out.keepLinesSet = keepLinesSet;
            out.widowControl = widowControl;
            out.widowControlSet = widowControlSet;
            out.numId = numId;
            out.ilvl = ilvl;
            out.rightTabTwips = rightTabTwips;
            out.tabLeader = tabLeader;
            out.autoSpaceDe = autoSpaceDe;
            out.autoSpaceDeSet = autoSpaceDeSet;
            out.autoSpaceDn = autoSpaceDn;
            out.autoSpaceDnSet = autoSpaceDnSet;
            out.wordWrap = wordWrap;
            out.wordWrapSet = wordWrapSet;
            out.overflowPunct = overflowPunct;
            out.overflowPunctSet = overflowPunctSet;
            return out;
        }

        public void set(ParagraphFormat source) {
            this.alignment = source.alignment;
            this.leftIndentTwips = source.leftIndentTwips;
            this.rightIndentTwips = source.rightIndentTwips;
            this.firstLineIndentTwips = source.firstLineIndentTwips;
            this.spacingBeforeTwips = source.spacingBeforeTwips;
            this.spacingAfterTwips = source.spacingAfterTwips;
            this.spacingBeforeLines = source.spacingBeforeLines;
            this.spacingAfterLines = source.spacingAfterLines;
            this.lineSpacingTwips = source.lineSpacingTwips;
            this.lineRule = source.lineRule;
            this.snapToGrid = source.snapToGrid;
            this.snapToGridSet = source.snapToGridSet;
            this.pageBreakBefore = source.pageBreakBefore;
            this.pageBreakBeforeSet = source.pageBreakBeforeSet;
            this.keepNext = source.keepNext;
            this.keepNextSet = source.keepNextSet;
            this.keepLines = source.keepLines;
            this.keepLinesSet = source.keepLinesSet;
            this.widowControl = source.widowControl;
            this.widowControlSet = source.widowControlSet;
            this.numId = source.numId;
            this.ilvl = source.ilvl;
            this.rightTabTwips = source.rightTabTwips;
            this.tabLeader = source.tabLeader;
            this.autoSpaceDe = source.autoSpaceDe;
            this.autoSpaceDeSet = source.autoSpaceDeSet;
            this.autoSpaceDn = source.autoSpaceDn;
            this.autoSpaceDnSet = source.autoSpaceDnSet;
            this.wordWrap = source.wordWrap;
            this.wordWrapSet = source.wordWrapSet;
            this.overflowPunct = source.overflowPunct;
            this.overflowPunctSet = source.overflowPunctSet;
        }

        public void merge(ParagraphFormat overlay) {
            if (overlay == null) return;
            if (overlay.alignment >= 0) alignment = overlay.alignment;
            if (overlay.leftIndentTwips >= 0) leftIndentTwips = overlay.leftIndentTwips;
            if (overlay.rightIndentTwips >= 0) rightIndentTwips = overlay.rightIndentTwips;
            if (overlay.firstLineIndentTwips != -1) firstLineIndentTwips = overlay.firstLineIndentTwips;
            if (overlay.spacingBeforeTwips >= 0) spacingBeforeTwips = overlay.spacingBeforeTwips;
            if (overlay.spacingAfterTwips >= 0) spacingAfterTwips = overlay.spacingAfterTwips;
            if (overlay.spacingBeforeLines >= 0) spacingBeforeLines = overlay.spacingBeforeLines;
            if (overlay.spacingAfterLines >= 0) spacingAfterLines = overlay.spacingAfterLines;
            if (overlay.lineSpacingTwips >= 0) lineSpacingTwips = overlay.lineSpacingTwips;
            if (overlay.lineRule != null && overlay.lineRule.length() > 0) lineRule = overlay.lineRule;
            if (overlay.snapToGridSet) { snapToGrid = overlay.snapToGrid; snapToGridSet = true; }
            if (overlay.pageBreakBeforeSet) { pageBreakBefore = overlay.pageBreakBefore; pageBreakBeforeSet = true; }
            if (overlay.keepNextSet) { keepNext = overlay.keepNext; keepNextSet = true; }
            if (overlay.keepLinesSet) { keepLines = overlay.keepLines; keepLinesSet = true; }
            if (overlay.widowControlSet) { widowControl = overlay.widowControl; widowControlSet = true; }
            if (overlay.numId >= 0) numId = overlay.numId;
            if (overlay.ilvl != 0) ilvl = overlay.ilvl;
            if (overlay.rightTabTwips >= 0) {
                rightTabTwips = overlay.rightTabTwips;
                tabLeader = overlay.tabLeader;
            }
            if (overlay.autoSpaceDeSet) { autoSpaceDe = overlay.autoSpaceDe; autoSpaceDeSet = true; }
            if (overlay.autoSpaceDnSet) { autoSpaceDn = overlay.autoSpaceDn; autoSpaceDnSet = true; }
            if (overlay.wordWrapSet) { wordWrap = overlay.wordWrap; wordWrapSet = true; }
            if (overlay.overflowPunctSet) { overflowPunct = overlay.overflowPunct; overflowPunctSet = true; }
        }
    }

    public static final class RangeStyle {
        public int start;
        public int end;
        public final RunStyle style = new RunStyle();
    }

    public static final class FieldCode {
        public String instruction = "";
        public String cachedResult = "";
        public boolean simple;
        /** Visible text range, UTF-16 offsets; -1 denotes unbound legacy input. */
        public int start = -1, end = -1;

        public FieldCode(String instruction, String cachedResult, boolean simple) {
            this.instruction = instruction == null ? "" : instruction.trim();
            this.cachedResult = cachedResult == null ? "" : cachedResult;
            this.simple = simple;
        }
    }

    public static final class DisplayHighlight {
        public int paragraphIndex, start, end;
        public int color = 0x66FFB74D;
    }

    public static final class Revision {
        public enum Kind { INSERT, DELETE, FORMAT, PARAGRAPH_FORMAT }
        public int id, paragraphIndex, start, end;
        public Kind kind = Kind.INSERT;
        public String author = "作者", date = "", text = "";
        public final ArrayList<Run> previousRuns = new ArrayList<Run>();
        public ParagraphFormat previousParagraphFormat;
        public String previousPropertiesXml = "";
    }

    public static final class Hyperlink {
        public int start, end;
        public String target = "";
        public String relationshipId = "";
        public String anchor = "";
    }

    public static final class Comment {
        public int id;
        public int paragraphIndex;
        public int start;
        public int end;
        public String author = "论文编辑器";
        public String date = "";
        public String text = "";
        public boolean resolved;
        public int parentId = -1;
        public String paraId = "";

        public Comment(int id, int paragraphIndex, int start, int end, String text) {
            this.id = id;
            this.paragraphIndex = paragraphIndex;
            this.start = start;
            this.end = end;
            this.text = text == null ? "" : text;
        }
    }

    public static final class EmbeddedImage {
        public final byte[] bytes;
        public final String name;
        public String relationshipId = "";
        public String partName = "";
        public int widthEmu = -1;
        public int heightEmu = -1;
        /** True for images inserted during this editing session; they need new parts, rels and content types. */
        public boolean isNew;

        public EmbeddedImage(byte[] bytes, String name) {
            this.bytes = bytes;
            this.name = name == null ? "" : name;
        }
    }

    public static final class SectionSettings {
        /** First body block belonging to this section. */
        public int startBlockIndex;
        public int pageWidthTwips = -1;
        public int pageHeightTwips = -1;
        public int marginTopTwips = -1;
        public int marginBottomTwips = -1;
        public int marginLeftTwips = -1;
        public int marginRightTwips = -1;
        /** Word gutter is added to the inside/left margin for non-mirrored pages. */
        public int gutterTwips = 0;
        public int headerDistanceTwips = -1;
        public int footerDistanceTwips = -1;
        /** Word document-grid baseline pitch in twips; -1 means no grid. */
        public int lineGridPitchTwips = -1;
        /**
         * False when w:docGrid carries no w:type, which is how OOXML spells "no grid": the pitch is then
         * a leftover and must not price anything. Word 16.0 says so - tools/word-spacing-truth.ps1 case
         * lh_single (no type: a 12pt SimSun row costs 15.6pt, not the declared 18pt pitch) versus case
         * bl_grid (type="lines": the same row costs 18.35pt).
         */
        public boolean lineGridActive;
        public boolean landscape;
        public String headerPart = "";
        public String footerPart = "";
        public String headerRelationshipId = "";
        public String footerRelationshipId = "";
        public String headerText = "";
        public String headerFontFamily = "";
        public float headerFontSizePt = 9f;
        public int headerAlignment = 1;
        public boolean headerHasBottomBorder;
        /** OOXML border size is in eighths of a point; space is in points. */
        public int headerBorderSizeEighthPt = 6;
        public int headerBorderSpacePt = 0;
        public String headerBorderValue = "double";
        /** %PAGE% and %NUMPAGES% are replaced by section-aware field values. */
        public String footerTemplate = "";
        public String footerFontFamily = "";
        public float footerFontSizePt = 10.5f;
        public int footerAlignment = 1;
        public String pageNumberFormat = "decimal";
        public int pageNumberStart = 1;

        public boolean hasPageSize() {
            return pageWidthTwips > 0 && pageHeightTwips > 0;
        }

        public void set(SectionSettings source) {
            SectionSettings copy = source == null ? new SectionSettings() : source;
            startBlockIndex = copy.startBlockIndex;
            pageWidthTwips = copy.pageWidthTwips;
            pageHeightTwips = copy.pageHeightTwips;
            marginTopTwips = copy.marginTopTwips;
            marginBottomTwips = copy.marginBottomTwips;
            marginLeftTwips = copy.marginLeftTwips;
            marginRightTwips = copy.marginRightTwips;
            gutterTwips = copy.gutterTwips;
            headerDistanceTwips = copy.headerDistanceTwips;
            footerDistanceTwips = copy.footerDistanceTwips;
            lineGridPitchTwips = copy.lineGridPitchTwips;
            lineGridActive = copy.lineGridActive;
            landscape = copy.landscape;
            headerPart = copy.headerPart;
            footerPart = copy.footerPart;
            headerRelationshipId = copy.headerRelationshipId;
            footerRelationshipId = copy.footerRelationshipId;
            headerText = copy.headerText;
            headerFontFamily = copy.headerFontFamily;
            headerFontSizePt = copy.headerFontSizePt;
            headerAlignment = copy.headerAlignment;
            headerHasBottomBorder = copy.headerHasBottomBorder;
            headerBorderSizeEighthPt = copy.headerBorderSizeEighthPt;
            headerBorderSpacePt = copy.headerBorderSpacePt;
            headerBorderValue = copy.headerBorderValue;
            footerTemplate = copy.footerTemplate;
            footerFontFamily = copy.footerFontFamily;
            footerFontSizePt = copy.footerFontSizePt;
            footerAlignment = copy.footerAlignment;
            pageNumberFormat = copy.pageNumberFormat;
            pageNumberStart = copy.pageNumberStart;
        }

        public SectionSettings copy() {
            SectionSettings out = new SectionSettings();
            out.startBlockIndex = startBlockIndex;
            out.pageWidthTwips = pageWidthTwips;
            out.pageHeightTwips = pageHeightTwips;
            out.marginTopTwips = marginTopTwips;
            out.marginBottomTwips = marginBottomTwips;
            out.marginLeftTwips = marginLeftTwips;
            out.marginRightTwips = marginRightTwips;
            out.gutterTwips = gutterTwips;
            out.headerDistanceTwips = headerDistanceTwips;
            out.footerDistanceTwips = footerDistanceTwips;
            out.lineGridPitchTwips = lineGridPitchTwips;
            out.lineGridActive = lineGridActive;
            out.landscape = landscape;
            out.headerPart = headerPart;
            out.footerPart = footerPart;
            out.headerRelationshipId = headerRelationshipId;
            out.footerRelationshipId = footerRelationshipId;
            out.headerText = headerText;
            out.headerFontFamily = headerFontFamily;
            out.headerFontSizePt = headerFontSizePt;
            out.headerAlignment = headerAlignment;
            out.headerHasBottomBorder = headerHasBottomBorder;
            out.headerBorderSizeEighthPt = headerBorderSizeEighthPt;
            out.headerBorderSpacePt = headerBorderSpacePt;
            out.headerBorderValue = headerBorderValue;
            out.footerTemplate = footerTemplate;
            out.footerFontFamily = footerFontFamily;
            out.footerFontSizePt = footerFontSizePt;
            out.footerAlignment = footerAlignment;
            out.pageNumberFormat = pageNumberFormat;
            out.pageNumberStart = pageNumberStart;
            return out;
        }
    }

    public static final class FormatIssue {
        public static final int ERROR = 2;
        public static final int WARNING = 1;
        public static final int INFO = 0;

        public final int severity;
        public final int blockIndex;
        public final String title;
        public final String detail;

        public FormatIssue(int severity, int blockIndex, String title, String detail) {
            this.severity = severity;
            this.blockIndex = blockIndex;
            this.title = title == null ? "" : title;
            this.detail = detail == null ? "" : detail;
        }
    }

    public int nonEmptyParagraphCount() {
        int count = 0;
        for (ParagraphBlock paragraph : paragraphs) if (!paragraph.isEmpty()) count++;
        return count;
    }

    public List<ParagraphBlock> paragraphList() {
        return paragraphs;
    }
}

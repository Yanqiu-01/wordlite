package com.rikkahub.wordlite;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.TextLayoutMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Page-level geometry regressions; these must not regress when line wrapping changes. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
@TextLayoutMode(TextLayoutMode.Mode.REALISTIC)
public class SectionPageLayoutTest {
    @Test
    public void headerAndFooterDistancesUsePhysicalPageAndLeaveBodyMarginsIntact() {
        DocxDocument.SectionSettings section = new DocxDocument.SectionSettings();
        section.pageWidthTwips = 11906;
        section.pageHeightTwips = 16838;
        section.marginTopTwips = 1440;
        section.marginBottomTwips = 1440;
        section.marginLeftTwips = 1800;
        section.marginRightTwips = 1800;
        section.headerDistanceTwips = 851;
        section.footerDistanceTwips = 992;

        PageGeometry geometry = new PageGeometry(section);
        float twips = PageGeometry.TWIPS_PER_UNIT;
        assertEquals(11906f / twips, geometry.width, 0.001f);
        assertEquals(16838f / twips, geometry.height, 0.001f);
        assertEquals(851f / twips, geometry.headerDistance, 0.001f);
        assertEquals(992f / twips, geometry.footerDistance, 0.001f);
        assertEquals(11906f / twips - 1800f / twips - 1800f / twips, geometry.contentWidth, 0.001f);
        assertEquals(16838f / twips - 1440f / twips - 1440f / twips, geometry.contentHeight, 0.001f);
        assertTrue(geometry.headerDistance < geometry.top);
        assertTrue(geometry.height - geometry.footerDistance > geometry.height - geometry.bottom);
    }

    @Test
    public void pageContentCarriesSectionHeaderBorderMetadata() {
        DocxDocument.SectionSettings section = new DocxDocument.SectionSettings();
        section.headerText = "哈尔滨工业大学本科毕业论文（设计）";
        section.footerTemplate = "- %PAGE% -";
        section.headerHasBottomBorder = true;
        section.headerBorderValue = "double";
        section.headerBorderSizeEighthPt = 6;
        section.headerBorderSpacePt = 1;

        DocxDocument.SectionSettings copy = section.copy();
        A4Paginator.PageContent page = new A4Paginator.PageContent();
        page.headerText = copy.headerText;
        page.footerTemplate = copy.footerTemplate;
        page.headerHasBottomBorder = copy.headerHasBottomBorder;
        page.headerBorderSizeEighthPt = copy.headerBorderSizeEighthPt;
        page.headerBorderSpacePt = copy.headerBorderSpacePt;
        page.headerBorderValue = copy.headerBorderValue;

        assertEquals("double", page.headerBorderValue);
        assertEquals(6, page.headerBorderSizeEighthPt);
        assertEquals(1, page.headerBorderSpacePt);
        assertTrue(page.headerHasBottomBorder);
    }

    @Test
    public void snapToGridRaisesAutoLine300ToSectionGrid360() {
        DocxTextLayout.initialize(RuntimeEnvironment.getApplication());
        DocxDocument.ParagraphBlock paragraph = new DocxDocument.ParagraphBlock();
        paragraph.text = "正文";
        paragraph.baseRunStyle.fontSizeHalfPoints = 24;
        paragraph.baseRunStyle.eastAsiaFontFamily = "宋体";
        paragraph.runs.add(new DocxDocument.Run(paragraph.text, paragraph.baseRunStyle.copy()));
        paragraph.format.lineSpacingTwips = 300;
        paragraph.format.lineRule = "auto";
        paragraph.format.snapToGrid = true;
        paragraph.format.snapToGridSet = true;
        android.text.StaticLayout layout = DocxTextLayout.measure(
                paragraph, 300, null, 360).layout;
        assertEquals(25, layout.getHeight());

        DocxDocument imageDocument = new DocxDocument();
        imageDocument.section.lineGridPitchTwips = 360;
        DocxDocument.ParagraphBlock imageParagraph = new DocxDocument.ParagraphBlock();
        imageParagraph.index = 0;
        imageParagraph.baseRunStyle.fontSizeHalfPoints = 24;
        imageParagraph.baseRunStyle.eastAsiaFontFamily = "宋体";
        imageParagraph.format.lineSpacingTwips = 300;
        imageParagraph.format.lineRule = "auto";
        imageParagraph.format.snapToGrid = true;
        imageParagraph.format.snapToGridSet = true;
        DocxDocument.EmbeddedImage tiny = new DocxDocument.EmbeddedImage(new byte[]{1}, "tiny");
        tiny.widthEmu = 9525;
        tiny.heightEmu = 9525;
        imageParagraph.images.add(tiny);
        imageDocument.blocks.add(imageParagraph);
        imageDocument.paragraphs.add(imageParagraph);
        A4Paginator.PageResult imageResult = new A4Paginator(imageDocument.section).paginate(imageDocument);
        A4Paginator.ParagraphLayout imageFragment = imageResult.pages.get(0).paragraphs.get(0);
        assertEquals(25.5f, imageFragment.height, 0.01f);
        assertTrue("image drawing height=" + imageFragment.imageHeight,
                imageFragment.imageHeight < imageFragment.height);
    }

    @Test
    public void pageCountUsesRealDocumentAndBodySectionFooterStartsAtOne() throws Exception {
        DocxTextLayout.initialize(RuntimeEnvironment.getApplication());
        java.io.File root = new java.io.File(System.getProperty("app.root"));
        DocxDocument document = DocxParser.parse(
                new java.io.FileInputStream(new java.io.File(root, "tests/samples/input-liu.docx")),
                "real.docx");
        assertEquals(3, document.sections.size());
        assertEquals("哈尔滨工业大学本科毕业论文（设计）", document.sections.get(2).headerText);
        assertEquals("- %PAGE% -", document.sections.get(2).footerTemplate);
        assertTrue(document.sections.get(2).headerHasBottomBorder);
        assertEquals(360, document.sections.get(2).lineGridPitchTwips);
        assertTrue(document.blocks.get(48) instanceof DocxDocument.ParagraphBlock);
        assertTrue(((DocxDocument.ParagraphBlock) document.blocks.get(48)).format.snapToGrid);
        assertEquals(300, ((DocxDocument.ParagraphBlock) document.blocks.get(48)).format.lineSpacingTwips);
        assertTrue(document.sections.get(2).headerBorderSizeEighthPt > 0);

        A4Paginator.PageResult result = new A4Paginator(document.section).paginate(document);
        assertTrue(result.totalPages() > 0);
        assertEquals(0, result.pages.get(0).sectionIndex);
        boolean sawBody = false;
        for (A4Paginator.PageContent page : result.pages) {
            if (page.sectionIndex != 2) continue;
            sawBody = true;
            assertEquals("- %PAGE% -", page.footerTemplate);
            assertEquals(1, page.displayedPageNumber);
            assertEquals(document.sections.get(2).pageWidthTwips / PageGeometry.TWIPS_PER_UNIT,
                    page.geometry.width, 0.001f);
            assertEquals(document.sections.get(2).pageHeightTwips / PageGeometry.TWIPS_PER_UNIT,
                    page.geometry.height, 0.001f);
            break;
        }
        assertTrue(sawBody);
    }
}

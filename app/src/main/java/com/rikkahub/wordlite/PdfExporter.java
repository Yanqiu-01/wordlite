package com.rikkahub.wordlite;

import android.content.Context;
import android.graphics.Canvas;

import android.text.Layout;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;

/** Native vector PDF using exactly the editor's shaped pages and selected fonts. */
public final class PdfExporter {
    private PdfExporter() { }
    public static void export(Context context, DocxDocument source, OutputStream output, PdfExportOptions options) throws Exception {
        if (source == null || output == null) throw new IllegalArgumentException("导出路径无效");
        DocxTextLayout.initialize(context);
        DocxDocument document = options.scope == PdfExportOptions.Scope.SELECTION
                ? DocumentSlices.selection(source, options) : source;
        if (options.includeRevisions) document = ReviewCopies.marked(document);
        A4Paginator.PageResult result = new A4Paginator(document.section).paginate(document);
        int[] range = options.pages(result.totalPages());
        if (options.scope == PdfExportOptions.Scope.SELECTION) {
            range = new int[result.totalPages()]; for (int i = 0; i < range.length; i++) range[i] = i;
        }
        ArrayList<PdfExtras.Bookmark> bookmarks = new ArrayList<PdfExtras.Bookmark>();
        ArrayList<PdfExtras.Annotation> annotations = new ArrayList<PdfExtras.Annotation>();
        HashSet<Integer> bookmarked = new HashSet<Integer>();
        PdfFile pdf = new PdfFile();
        int catalog = pdf.reserve(), pageTree = pdf.reserve();
        PdfCanvas.Resources resources = new PdfCanvas.Resources(pdf, context);
        ArrayList<Integer> pageIds = new ArrayList<Integer>();
        ArrayList<Integer> contents = new ArrayList<Integer>();
        ArrayList<int[]> dimensions = new ArrayList<int[]>();
        {
            for (int outputPage = 0; outputPage < range.length; outputPage++) {
                int index = range[outputPage];
                A4Paginator.PageContent content = result.pages.get(index);
                PageGeometry geometry = content.geometry == null ? result.geometry : content.geometry;
                int width = Math.round(geometry.width * .75f), height = Math.round(geometry.height * .75f);
                PdfCanvas canvas = new PdfCanvas(resources, height);
                PaperPageView renderer = new PaperPageView(context, document, content, geometry,
                        index + 1, result.totalPages(), (block, offset) -> {});
                renderer.showRevisions(false);
                renderer.render(canvas, .75f, options.includeComments, false);
                metadata(content, document, geometry, outputPage, height, options, bookmarked, bookmarks, annotations);
                int contentId = pdf.reserve(); pdf.stream(contentId, "", canvas.bytes(), true);
                pageIds.add(pdf.reserve()); contents.add(contentId); dimensions.add(new int[]{width, height});
            }
        }
        resources.finish();
        StringBuilder kids = new StringBuilder("[");
        for (int i = 0; i < pageIds.size(); i++) {
            int[] size = dimensions.get(i);
            pdf.set(pageIds.get(i), "<< /Type /Page /Parent " + pageTree + " 0 R /MediaBox [0 0 " + size[0] + ' ' + size[1]
                    + "] /Resources " + resources.dictionary() + " /Contents " + contents.get(i) + " 0 R >>");
            kids.append(pageIds.get(i)).append(" 0 R ");
        }
        kids.append(']');
        pdf.set(pageTree, "<< /Type /Pages /Kids " + kids + " /Count " + pageIds.size() + " >>");
        pdf.set(catalog, "<< /Type /Catalog /Pages " + pageTree + " 0 R >>");
        byte[] raw = pdf.finish(catalog);
        if (raw.length < 20 || raw[0] != '%' || raw[1] != 'P') throw new IOException("PDF生成失败");
        output.write(PdfExtras.append(raw, bookmarks, annotations));
        output.flush();
    }

    private static void metadata(A4Paginator.PageContent page, DocxDocument document, PageGeometry geometry,
                                 int outputPage, int pageHeight, PdfExportOptions options,
                                 HashSet<Integer> seen, ArrayList<PdfExtras.Bookmark> bookmarks,
                                 ArrayList<PdfExtras.Annotation> annotations) {
        for (A4Paginator.ParagraphLayout fragment : page.paragraphs) {
            if (fragment.text != null) fragmentMetadata(fragment.text, fragment.top,
                    fragment.startLine, fragment.endLine, geometry.left + fragment.text.x, geometry,
                    outputPage, pageHeight, document, options, seen, bookmarks, annotations);
            if (fragment.row != null) for (A4Paginator.CellParagraph cell : fragment.row.paragraphs)
                fragmentMetadata(cell.text, fragment.top + cell.y, 0, cell.text.layout.getLineCount(),
                        geometry.left + cell.x + cell.text.x, geometry, outputPage, pageHeight,
                        document, options, seen, bookmarks, annotations);
        }
    }
    private static void fragmentMetadata(DocxTextLayout.Paragraph shaped, float top, int first, int last,
                                         float left, PageGeometry geometry, int page, int pageHeight,
                                         DocxDocument document, PdfExportOptions options,
                                         HashSet<Integer> seen, ArrayList<PdfExtras.Bookmark> bookmarks,
                                         ArrayList<PdfExtras.Annotation> annotations) {
        if (options.bookmarks && shaped.source.isHeading && seen.add(shaped.source.index)) {
            PdfExtras.Bookmark bookmark = new PdfExtras.Bookmark();
            bookmark.title = shaped.source.text; bookmark.page = page;
            bookmark.top = pageHeight - (geometry.top + top) * .75f; bookmarks.add(bookmark);
        }
        for (DocxDocument.Hyperlink link : shaped.source.hyperlinks) {
            try {
                String scheme = URI.create(link.target).getScheme();
                if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme) && !"mailto".equalsIgnoreCase(scheme)) continue;
                addRange(shaped, link.start, link.end, first, last, left, geometry.top + top,
                        page, pageHeight, link.target, "", "", annotations);
            } catch (IllegalArgumentException ignored) { }
        }
        if (options.includeComments) for (DocxDocument.Comment comment : document.comments) {
            if (comment.parentId >= 0 || comment.paragraphIndex != shaped.source.index || comment.resolved) continue;
            StringBuilder thread = new StringBuilder(comment.text);
            for (DocxDocument.Comment reply : document.comments)
                if (reply.parentId == comment.id) thread.append('\n').append(reply.author).append(" · ").append(reply.date).append('\n').append(reply.text);
            addRange(shaped, comment.start, comment.end, first, last, left, geometry.top + top,
                    page, pageHeight, "", thread.toString(), comment.author, annotations);
        }
    }
    private static void addRange(DocxTextLayout.Paragraph shaped, int start, int end, int first, int last,
                                 float x, float y, int page, int height, String uri, String text, String author,
                                 ArrayList<PdfExtras.Annotation> annotations) {
        int[] range = shaped.displayRange(start, end);
        Layout layout = shaped.layout;
        for (int line = first; line < last; line++) {
            int lo = Math.max(range[0], layout.getLineStart(line)), hi = Math.min(range[1], layout.getLineEnd(line));
            if (hi <= lo) continue;
            float a = layout.getPrimaryHorizontal(lo), b = layout.getPrimaryHorizontal(hi);
            PdfExtras.Annotation annotation = new PdfExtras.Annotation();
            annotation.page = page; annotation.left = (x + Math.min(a, b)) * .75f;
            annotation.right = (x + Math.max(a, b)) * .75f;
            annotation.top = height - (y + layout.getLineTop(line) - layout.getLineTop(first)) * .75f;
            annotation.bottom = height - (y + layout.getLineBottom(line) - layout.getLineTop(first)) * .75f;
            annotation.uri = uri; annotation.text = text; annotation.author = author; annotations.add(annotation);
            if (!text.isEmpty()) break;
        }
    }
}

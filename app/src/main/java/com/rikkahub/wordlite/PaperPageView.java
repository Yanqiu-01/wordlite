package com.rikkahub.wordlite;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;
import java.util.IdentityHashMap;

/** Draws the paginated document body plus its section's real header/footer. */
public final class PaperPageView extends View {
    public interface Listener {
        void edit(int paragraphIndex, int offset);
        default void image(int paragraphIndex, DocxDocument.EmbeddedImage image) { }
        default void comment(DocxDocument.Comment comment) { }
    }
    private final A4Paginator.PageContent page;
    private final PageGeometry geometry;
    private final DocxDocument document;
    private final Listener listener;
    private final int number, total;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final IdentityHashMap<DocxDocument.EmbeddedImage, Bitmap> images = new IdentityHashMap<DocxDocument.EmbeddedImage, Bitmap>();
    private float downX, downY;
    private boolean drawComments = true;
    private boolean drawTransientHighlights = true;
    private final java.util.LinkedHashMap<DocxDocument.Comment, RectF> bubbles = new java.util.LinkedHashMap<DocxDocument.Comment, RectF>();
    private boolean drawRevisions = true;
    public void showRevisions(boolean value) { drawRevisions = value; invalidate(); }

    public PaperPageView(Context context, DocxDocument document, A4Paginator.PageContent page,
                         PageGeometry geometry, int number, int total, Listener listener) {
        super(context);
        this.document = document;
        this.page = page;
        this.geometry = page != null && page.geometry != null ? page.geometry : geometry;
        this.number = number;
        this.total = total;
        this.listener = listener;
        setBackgroundColor(Color.WHITE);
        setClickable(true);
        setContentDescription("第 " + number + " / " + total + " 页");
        highlightPaint.setColor(0x66FFD54F);
        imagePaint.setFilterBitmap(true);
        imagePaint.setDither(true);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width, Math.max(1, Math.round(width * geometry.height / geometry.width)));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        render(canvas, geometry.fitScale(getWidth()), true, true);
    }

    /** Shared vector drawing for screen and PDF; no separate PDF text layout. */
    public void render(Canvas canvas, float scale, boolean comments, boolean uiFooter) {
        drawComments = comments;
        drawTransientHighlights = uiFooter;
        canvas.save();
        canvas.scale(scale, scale);

        // Header/footer are positioned against the physical page, not the body clip.
        drawHeader(canvas);

        canvas.save();
        canvas.clipRect(geometry.left, geometry.top,
                geometry.width - geometry.right, geometry.height - geometry.bottom);
        canvas.translate(geometry.left, geometry.top);
        for (A4Paginator.ParagraphLayout f : page.paragraphs) {
            canvas.save();
            canvas.translate(0, f.top);
            canvas.clipRect(0, 0, geometry.contentWidth, f.height);
            if (f.text != null) {
                canvas.translate(f.text.x, -f.text.layout.getLineTop(f.startLine));
                drawParagraph(canvas, f.text);
            } else if (f.image != null) {
                Bitmap image = decodeImage(f.image);
                if (image != null) {
                    float drawWidth = f.imageWidth > 0 ? f.imageWidth : geometry.contentWidth;
                    float drawHeight = f.imageHeight > 0 ? f.imageHeight
                            : (f.height > 0 ? f.height
                            : drawWidth * image.getHeight() / Math.max(1f, image.getWidth()));
                    float imageTop = Math.max(0, f.height - drawHeight);
                    canvas.drawBitmap(image, null, new RectF(0, imageTop, drawWidth, imageTop + drawHeight), imagePaint);
                } else {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(1.2f);
                    paint.setColor(0xFFB54726);
                    canvas.drawRect(1, 1, Math.max(2, f.imageWidth), Math.max(2, f.height), paint);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setTextSize(10);
                    canvas.drawText("图片无法解码", 6, Math.min(Math.max(14, f.height - 5), 18), paint);
                }
            } else if (f.row != null) {
                paint.setColor(0xFF888888);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(0.6f);
                for (int i = 0; i < f.row.columns; i++)
                    canvas.drawRect(i * f.row.cellWidth, 0,
                            (i + 1) * f.row.cellWidth, f.height, paint);
                paint.setStyle(Paint.Style.FILL);
                for (A4Paginator.CellParagraph cp : f.row.paragraphs) {
                    canvas.save();
                    canvas.translate(cp.x + cp.text.x, cp.y);
                    drawParagraph(canvas, cp.text);
                    canvas.restore();
                }
                for (A4Paginator.CellImage ci : f.row.images) {
                    Bitmap image = decodeImage(ci.image);
                    if (image != null) {
                        canvas.drawBitmap(image, null,
                                new RectF(ci.x, ci.y, ci.x + ci.width, ci.y + ci.height), imagePaint);
                    } else {
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setColor(0xFFB54726);
                        canvas.drawRect(ci.x, ci.y, ci.x + ci.width, ci.y + ci.height, paint);
                        paint.setStyle(Paint.Style.FILL);
                    }
                }
            }
            canvas.restore();
        }
        canvas.restore();

        // Keep an app-only indicator only for documents that genuinely have no footer.
        // A DOCX PAGE field must never be duplicated by this UI indicator.
        if (uiFooter && !page.hasFooterPart && (page.footerTemplate == null || page.footerTemplate.length() == 0)) {
            paint.setColor(page.overflow ? 0xFFB54726 : 0xFF888888);
            paint.setTextSize(11);
            paint.setTypeface(Typeface.DEFAULT);
            String footer = page.overflow ? "超高对象未完整显示 · " + number + "/" + total
                    : number + " / " + total;
            canvas.drawText(footer, geometry.width / 2 - paint.measureText(footer) / 2,
                    geometry.height - 10, paint);
        }
        drawFooter(canvas);
        bubbles.clear();
        if (uiFooter && comments) drawCommentBubbles(canvas);
        canvas.restore();
    }

    private void drawCommentBubbles(Canvas canvas) {
        float previous = -100;
        for (DocxDocument.Comment comment : document.comments) {
            if (comment.parentId >= 0 || comment.resolved) continue;
            for (A4Paginator.ParagraphLayout fragment : page.paragraphs) {
                if (fragment.text == null || fragment.blockIndex != comment.paragraphIndex) continue;
                int[] range = fragment.text.displayRange(comment.start, comment.end);
                int start = Math.max(fragment.startChar, range[0]);
                if (start >= fragment.endChar || range[1] < fragment.startChar) continue;
                int line = fragment.text.layout.getLineForOffset(start);
                float y = geometry.top + fragment.top + fragment.text.layout.getLineTop(line)
                        - fragment.text.layout.getLineTop(fragment.startLine);
                y = Math.max(y, previous + 18); previous = y;
                float x = geometry.width - geometry.right + 8;
                RectF bounds = new RectF(x, y, x + 18, y + 14);
                bubbles.put(comment, bounds);
                paint.setColor(0xFF2D6BA7); paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(bounds, 2, 2, paint);
                paint.setColor(Color.WHITE); paint.setTextSize(8); paint.setTypeface(Typeface.DEFAULT);
                canvas.drawText("…", x + 4, y + 10, paint);
                break;
            }
        }
    }

    private void drawHeader(Canvas canvas) {
        if (page.headerText == null || page.headerText.length() == 0) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        paint.setTextSize(PageGeometry.points(page.headerFontSizePt > 0 ? page.headerFontSizePt : 9f));
        paint.setTypeface(DocxTextLayout.resolve(page.headerFontFamily));
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = geometry.headerDistance - fm.ascent;
        drawAlignedLines(canvas, page.headerText, baseline, page.headerAlignment, fm);
        if (page.headerHasBottomBorder) {
            float lastBaseline = baseline + (lineCount(page.headerText) - 1) * paint.getFontSpacing();
            float space = PageGeometry.points(Math.max(0, page.headerBorderSpacePt));
            float y = lastBaseline + fm.descent + space;
            float rule = PageGeometry.points(Math.max(0.25f, page.headerBorderSizeEighthPt / 8f));
            paint.setStyle(Paint.Style.STROKE);
            boolean doubleLine = page.headerBorderValue == null
                    || "double".equalsIgnoreCase(page.headerBorderValue);
            if (doubleLine) {
                paint.setStrokeWidth(rule);
                canvas.drawLine(geometry.left, y, geometry.width - geometry.right, y, paint);
                canvas.drawLine(geometry.left, y + rule * 2.5f,
                        geometry.width - geometry.right, y + rule * 2.5f, paint);
            } else {
                paint.setStrokeWidth(rule);
                canvas.drawLine(geometry.left, y, geometry.width - geometry.right, y, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void drawFooter(Canvas canvas) {
        if (page.footerTemplate == null || page.footerTemplate.length() == 0) return;
        String value = page.footerTemplate.replace("%PAGE%", formatPageNumber(page.displayedPageNumber,
                        page.pageNumberFormat))
                .replace("%NUMPAGES%", formatPageNumber(total, page.pageNumberFormat));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        paint.setTextSize(PageGeometry.points(page.footerFontSizePt > 0 ? page.footerFontSizePt : 10.5f));
        paint.setTypeface(DocxTextLayout.resolve(page.footerFontFamily));
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = geometry.height - geometry.footerDistance - fm.descent;
        drawAlignedLines(canvas, value, baseline, page.footerAlignment, fm);
    }

    private void drawAlignedLines(Canvas canvas, String value, float baseline,
                                  int alignment, Paint.FontMetrics fm) {
        String[] lines = value.replace("\r", "").split("\n", -1);
        float spacing = paint.getFontSpacing();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            float x;
            if (alignment == 2) x = geometry.width - geometry.right - paint.measureText(line);
            else if (alignment == 1 || alignment == 3)
                x = (geometry.left + geometry.width - geometry.right - paint.measureText(line)) / 2f;
            else x = geometry.left;
            canvas.drawText(line, x, baseline + i * spacing, paint);
        }
    }

    private int lineCount(String text) { return text.replace("\r", "").split("\n", -1).length; }

    private String formatPageNumber(int value, String format) {
        int safe = Math.max(1, value);
        if (format == null) return String.valueOf(safe);
        String lower = format.toLowerCase(java.util.Locale.US);
        if (lower.contains("roman")) {
            String roman = roman(safe);
            return lower.contains("upper") ? roman : roman.toLowerCase(java.util.Locale.US);
        }
        return String.valueOf(safe);
    }

    private String roman(int value) {
        int[] nums = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] chars = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder out = new StringBuilder();
        int n = value;
        for (int i = 0; i < nums.length; i++) while (n >= nums[i]) { out.append(chars[i]); n -= nums[i]; }
        return out.toString();
    }

    private Bitmap decodeImage(DocxDocument.EmbeddedImage source) {
        if (source == null || source.bytes == null || source.bytes.length == 0) return null;
        Bitmap cached = images.get(source);
        if (cached != null) return cached;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(source.bytes, 0, source.bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        options.inScaled = false;
        while (bounds.outWidth / options.inSampleSize > 2048
                || bounds.outHeight / options.inSampleSize > 2048) options.inSampleSize *= 2;
        Bitmap decoded = BitmapFactory.decodeByteArray(source.bytes, 0, source.bytes.length, options);
        if (decoded != null) images.put(source, decoded);
        return decoded;
    }

    private void drawParagraph(Canvas canvas, DocxTextLayout.Paragraph paragraph) {
        if (drawTransientHighlights) for (DocxDocument.DisplayHighlight highlight : document.displayHighlights) {
            if (highlight.paragraphIndex != paragraph.source.index) continue;
            int[] range = paragraph.displayRange(Math.max(0, highlight.start), Math.min(paragraph.source.text.length(), highlight.end));
            if (range[1] <= range[0]) continue;
            Path path = new Path(); paragraph.layout.getSelectionPath(range[0], range[1], path);
            highlightPaint.setColor(highlight.color); canvas.drawPath(path, highlightPaint); highlightPaint.setColor(0x66FFD54F);
        }
        if (drawComments) for (DocxDocument.Comment comment : document.comments) {
            if (comment.resolved || comment.parentId >= 0 || comment.paragraphIndex != paragraph.source.index) continue;
            int[] range = paragraph.displayRange(comment.start, comment.end);
            int start = range[0], end = range[1];
            if (end <= start) continue;
            Path path = new Path();
            paragraph.layout.getSelectionPath(start, end, path);
            canvas.drawPath(path, highlightPaint);
        }
        if (drawRevisions) for (DocxDocument.Revision revision : document.revisions) {
            if (revision.paragraphIndex != paragraph.source.index) continue;
            int[] range = paragraph.displayRange(revision.start, revision.end);
            if (range[1] > range[0]) {
                Path path = new Path(); paragraph.layout.getSelectionPath(range[0], range[1], path);
                highlightPaint.setColor(revision.kind == DocxDocument.Revision.Kind.INSERT ? 0x3318A05A : 0x333066C0);
                canvas.drawPath(path, highlightPaint); highlightPaint.setColor(0x66FFD54F);
            } else if (revision.kind == DocxDocument.Revision.Kind.DELETE && paragraph.layout.getText().length() > 0) {
                int at = Math.max(0, Math.min(revision.start, paragraph.layout.getText().length()));
                int line = paragraph.layout.getLineForOffset(at); float x = paragraph.layout.getPrimaryHorizontal(at);
                paint.setColor(0xFFB13232); paint.setStrokeWidth(1.5f);
                canvas.drawLine(x, paragraph.layout.getLineTop(line), x, paragraph.layout.getLineBottom(line), paint);
            }
        }
        paragraph.layout.draw(canvas);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX(); downY = event.getY(); return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (Math.abs(event.getX() - downX) + Math.abs(event.getY() - downY)
                    > 20 * getResources().getDisplayMetrics().density) return true;
            performClick();
            float scale = geometry.fitScale(getWidth());
            float docX = event.getX() / scale, docY = event.getY() / scale;
            for (java.util.Map.Entry<DocxDocument.Comment, RectF> entry : bubbles.entrySet())
                if (entry.getValue().contains(docX, docY)) { listener.comment(entry.getKey()); return true; }
            float x = docX - geometry.left;
            float y = event.getY() / scale - geometry.top;
            for (A4Paginator.ParagraphLayout f : page.paragraphs) {
                if (y < f.top || y > f.top + f.height) continue;
                if (f.text != null) {
                    int line = f.text.layout.getLineForVertical((int) (y - f.top + f.text.layout.getLineTop(f.startLine)));
                    line = Math.max(f.startLine, Math.min(f.endLine - 1, line));
                    int displayOffset = f.text.layout.getOffsetForHorizontal(line, x - f.text.x);
                    listener.edit(f.blockIndex, f.text.sourceOffset(displayOffset));
                    return true;
                }
                if (f.image != null) { listener.image(f.blockIndex, f.image); return true; }
                if (f.row != null) for (A4Paginator.CellParagraph cp : f.row.paragraphs) {
                    if (x < cp.x - 4 || x >= cp.x - 4 + f.row.cellWidth
                            || y - f.top < cp.y || y - f.top > cp.y + cp.text.layout.getHeight()) continue;
                    int line = cp.text.layout.getLineForVertical((int) (y - f.top - cp.y));
                    listener.edit(cp.text.source.index,
                            cp.text.layout.getOffsetForHorizontal(line, x - cp.x - cp.text.x));
                    return true;
                }
            }
            return true;
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }
}

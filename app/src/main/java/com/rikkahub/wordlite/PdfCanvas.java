package com.rikkahub.wordlite;

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.text.TextPaint;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Records the shared Android page renderer as PDF vector/text/image operations. */
public final class PdfCanvas extends Canvas {
    public static final class Resources {
        final PdfFile pdf;
        final Context context;
        final LinkedHashMap<String, PdfTrueType> fonts = new LinkedHashMap<String, PdfTrueType>();
        final LinkedHashMap<String, Integer> images = new LinkedHashMap<String, Integer>();
        final LinkedHashMap<Integer, Integer> alphas = new LinkedHashMap<Integer, Integer>();
        Resources(PdfFile pdf, Context context) { this.pdf = pdf; this.context = context; }
        PdfTrueType font(Paint paint) throws IOException {
            String path = FontManager.pathFor(paint.getTypeface());
            if (path == null) path = DocxFontAssets.TIMES;
            PdfTrueType font = fonts.get(path);
            if (font != null) return font;
            byte[] bytes;
            try (InputStream in = context.getAssets().open(path)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[16384]; int count;
                while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
                bytes = out.toByteArray();
            }
            font = new PdfTrueType(bytes, path, "F" + fonts.size()); font.reserve(pdf); fonts.put(path, font);
            return font;
        }
        String dictionary() {
            StringBuilder value = new StringBuilder("<< /Font << ");
            for (PdfTrueType font : fonts.values()) value.append('/').append(font.resource).append(' ').append(font.reserve(pdf)).append(" 0 R ");
            value.append(">> /XObject << ");
            for (Map.Entry<String, Integer> image : images.entrySet()) value.append('/').append(image.getKey()).append(' ').append(image.getValue()).append(" 0 R ");
            value.append(">> /ExtGState << ");
            for (Map.Entry<Integer, Integer> alpha : alphas.entrySet()) value.append("/A").append(alpha.getKey()).append(' ').append(alpha.getValue()).append(" 0 R ");
            return value.append(">> >>").toString();
        }
        void finish() throws IOException { for (PdfTrueType font : fonts.values()) font.finish(pdf); }
    }
    private final Resources resources;
    private final StringBuilder operations = new StringBuilder();
    private int saves;
    PdfCanvas(Resources resources, int height) {
        this.resources = resources; operations.append("1 0 0 -1 0 ").append(height).append(" cm\n");
    }
    byte[] bytes() { return operations.toString().getBytes(StandardCharsets.ISO_8859_1); }
    @Override public int save() { operations.append("q\n"); return ++saves; }
    @Override public void restore() { if (saves > 0) { operations.append("Q\n"); saves--; } }
    @Override public void restoreToCount(int count) { while (saves >= count && saves > 0) restore(); }
    @Override public void translate(float x, float y) { operations.append("1 0 0 1 ").append(n(x)).append(' ').append(n(y)).append(" cm\n"); }
    @Override public void scale(float x, float y) { operations.append(n(x)).append(" 0 0 ").append(n(y)).append(" 0 0 cm\n"); }
    @Override public void concat(Matrix matrix) {
        float[] values = new float[9]; matrix.getValues(values);
        operations.append(n(values[0])).append(' ').append(n(values[3])).append(' ').append(n(values[1])).append(' ')
                .append(n(values[4])).append(' ').append(n(values[2])).append(' ').append(n(values[5])).append(" cm\n");
    }
    @Override public boolean clipRect(float left, float top, float right, float bottom) {
        rect(left, top, right, bottom); operations.append("W n\n"); return true;
    }
    @Override public boolean clipRect(Rect rectangle) { return clipRect(rectangle.left, rectangle.top, rectangle.right, rectangle.bottom); }
    @Override public boolean clipRect(RectF rectangle) { return clipRect(rectangle.left, rectangle.top, rectangle.right, rectangle.bottom); }
    @Override public boolean clipRect(int left, int top, int right, int bottom) { return clipRect((float) left, top, right, bottom); }
    @Override public boolean getClipBounds(Rect bounds) { bounds.set(-100000, -100000, 100000, 100000); return true; }
    @Override public void drawColor(int color) { }
    @Override public void drawLine(float x1, float y1, float x2, float y2, Paint paint) {
        style(paint); operations.append(n(x1)).append(' ').append(n(y1)).append(" m ").append(n(x2)).append(' ').append(n(y2)).append(" l S\n");
    }
    @Override public void drawRect(float left, float top, float right, float bottom, Paint paint) {
        style(paint); rect(left, top, right, bottom); fill(paint);
    }
    @Override public void drawRect(RectF rectangle, Paint paint) { drawRect(rectangle.left, rectangle.top, rectangle.right, rectangle.bottom, paint); }
    @Override public void drawRect(Rect rectangle, Paint paint) { drawRect(rectangle.left, rectangle.top, rectangle.right, rectangle.bottom, paint); }
    @Override public void drawPath(Path path, Paint paint) {
        if (Build.VERSION.SDK_INT < 26) return;
        style(paint);
        float[] points = path.approximate(.25f); float fraction = -1;
        for (int i = 0; i < points.length; i += 3) {
            operations.append(n(points[i + 1])).append(' ').append(n(points[i + 2]))
                    .append(i == 0 || fraction == points[i] ? " m\n" : " l\n"); fraction = points[i];
        }
        fill(paint);
    }
    @Override public void drawText(String text, float x, float y, Paint paint) { text(text, 0, text.length(), x, y, paint); }
    @Override public void drawText(String text, int start, int end, float x, float y, Paint paint) { text(text, start, end, x, y, paint); }
    @Override public void drawText(CharSequence text, int start, int end, float x, float y, Paint paint) { text(text.toString(), start, end, x, y, paint); }
    @Override public void drawText(char[] text, int start, int count, float x, float y, Paint paint) { text(new String(text, start, count), 0, count, x, y, paint); }
    @Override public void drawTextRun(CharSequence text, int start, int end, int contextStart, int contextEnd, float x, float y, boolean rtl, Paint paint) {
        text(text.toString(), start, end, x, y, paint);
    }
    @Override public void drawTextRun(char[] text, int start, int count, int contextStart, int contextCount, float x, float y, boolean rtl, Paint paint) {
        text(new String(text, start, count), 0, count, x, y, paint);
    }
    private void text(String text, int start, int end, float x, float y, Paint paint) {
        if (start >= end) return;
        try {
            PdfTrueType font = resources.font(paint); style(paint);
            float[] advances = new float[end - start];
            paint.getTextRunAdvances(text.toCharArray(), start, end - start, start, end - start, false, advances, 0);
            String value = text.substring(start, end); float position = x;
            if (paint.getTextAlign() == Paint.Align.CENTER) position -= paint.measureText(value) / 2;
            if (paint.getTextAlign() == Paint.Align.RIGHT) position -= paint.measureText(value);
            if (Build.VERSION.SDK_INT >= 31) {
                android.graphics.text.PositionedGlyphs shaped = android.graphics.text.TextRunShaper.shapeTextRun(
                        text, start, end - start, start, end - start, position, y, false, paint);
                for (int at = start; at < end; ) {
                    int cp = text.codePointAt(at), gid = font.glyph(cp);
                    if (gid != 0) font.use(gid, new String(Character.toChars(cp)));
                    at += Character.charCount(cp);
                }
                for (int i = 0; i < shaped.glyphCount(); i++) {
                    int gid = shaped.getGlyphId(i);
                    if (gid == 0) throw new IOException("PDF缺少字形");
                    font.use(gid, ""); glyph(font, gid, shaped.getGlyphX(i), shaped.getGlyphY(i), paint);
                }
                position += shaped.getAdvance();
            } else for (int at = start; at < end; ) {
                int codePoint = text.codePointAt(at), chars = Character.charCount(codePoint);
                int glyph = font.glyph(codePoint);
                if (glyph == 0) throw new IOException("PDF字体缺少字符 U+" + Integer.toHexString(codePoint));
                font.use(glyph, new String(Character.toChars(codePoint)));
                glyph(font, glyph, position, y, paint);
                for (int k = 0; k < chars; k++) position += advances[at + k - start];
                at += chars;
            }
            float length = position - x;
            if (paint.isUnderlineText()) drawLine(x, y + paint.getTextSize() * .08f, x + length, y + paint.getTextSize() * .08f, paint);
            if (paint.isStrikeThruText()) drawLine(x, y - paint.getTextSize() * .3f, x + length, y - paint.getTextSize() * .3f, paint);
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
    private void glyph(PdfTrueType font, int glyph, float x, float y, Paint paint) {
        operations.append("BT /").append(font.resource).append(' ').append(n(paint.getTextSize())).append(" Tf ")
                .append(n(paint.getTextScaleX())).append(" 0 ").append(n(-paint.getTextSkewX())).append(" -1 ")
                .append(n(x)).append(' ').append(n(y)).append(" Tm <")
                .append(String.format(java.util.Locale.US, "%04X", glyph)).append("> Tj ET\n");
    }
    @Override public void drawBitmap(Bitmap bitmap, Rect src, RectF dst, Paint paint) { bitmap(bitmap, src, dst); }
    @Override public void drawBitmap(Bitmap bitmap, Rect src, Rect dst, Paint paint) { bitmap(bitmap, src, new RectF(dst)); }
    @Override public void drawBitmap(Bitmap bitmap, float x, float y, Paint paint) { bitmap(bitmap, null, new RectF(x, y, x + bitmap.getWidth(), y + bitmap.getHeight())); }
    private void bitmap(Bitmap bitmap, Rect src, RectF dst) {
        if (bitmap == null) return;
        try {
            int left = src == null ? 0 : src.left, top = src == null ? 0 : src.top;
            int width = src == null ? bitmap.getWidth() : src.width(), height = src == null ? bitmap.getHeight() : src.height();
            int[] pixels = new int[width * height]; bitmap.getPixels(pixels, 0, width, left, top, width, height);
            byte[] rgb = new byte[pixels.length * 3], alpha = new byte[pixels.length]; boolean transparent = false;
            for (int i = 0; i < pixels.length; i++) {
                rgb[i * 3] = (byte) (pixels[i] >>> 16); rgb[i * 3 + 1] = (byte) (pixels[i] >>> 8); rgb[i * 3 + 2] = (byte) pixels[i];
                alpha[i] = (byte) (pixels[i] >>> 24); transparent |= (pixels[i] >>> 24) != 255;
            }
            String name = "Im" + resources.images.size(); int id = resources.pdf.reserve();
            String mask = "";
            if (transparent) {
                int maskId = resources.pdf.reserve(); resources.pdf.stream(maskId, "/Type /XObject /Subtype /Image /Width " + width
                        + " /Height " + height + " /BitsPerComponent 8 /ColorSpace /DeviceGray", alpha, true);
                mask = " /SMask " + maskId + " 0 R";
            }
            resources.pdf.stream(id, "/Type /XObject /Subtype /Image /Width " + width + " /Height " + height
                    + " /BitsPerComponent 8 /ColorSpace /DeviceRGB" + mask, rgb, true);
            resources.images.put(name, id);
            operations.append("q ").append(n(dst.width())).append(" 0 0 ").append(n(-dst.height())).append(' ')
                    .append(n(dst.left)).append(' ').append(n(dst.bottom)).append(" cm /").append(name).append(" Do Q\n");
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
    private void rect(float left, float top, float right, float bottom) {
        operations.append(n(left)).append(' ').append(n(top)).append(' ').append(n(right - left)).append(' ').append(n(bottom - top)).append(" re\n");
    }
    private void style(Paint paint) {
        int color = paint.getColor(); int alpha = paint.getAlpha();
        Integer alphaId = resources.alphas.get(alpha);
        if (alphaId == null) {
            alphaId = resources.pdf.add("<< /Type /ExtGState /ca " + n(alpha / 255f) + " /CA " + n(alpha / 255f) + " >>");
            resources.alphas.put(alpha, alphaId);
        }
        String rgb = n(Color.red(color) / 255f) + ' ' + n(Color.green(color) / 255f) + ' ' + n(Color.blue(color) / 255f);
        operations.append("/A").append(alpha).append(" gs ").append(rgb).append(" rg ").append(rgb).append(" RG ")
                .append(n(Math.max(.1f, paint.getStrokeWidth()))).append(" w\n");
    }
    private void fill(Paint paint) {
        operations.append(paint.getStyle() == Paint.Style.STROKE ? "S\n" : paint.getStyle() == Paint.Style.FILL_AND_STROKE ? "B\n" : "f\n");
    }
    private static String n(float value) { return PdfFile.number(value); }
}

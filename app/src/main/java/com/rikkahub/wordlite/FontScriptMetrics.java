package com.rikkahub.wordlite;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** OpenType OS/2 script recommendations and line-height metrics, in em units. */
public final class FontScriptMetrics {
    public static final FontScriptMetrics DEFAULT = new FontScriptMetrics(.65f, .30f, .65f, .15f, 0.8f, 0.2f);
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    public final float superscriptScale, superscriptOffset, subscriptScale, subscriptOffset;
    /** Word's single-line height ratio: (winAscent + winDescent) / unitsPerEm. */
    public final float lineHeightRatio;
    /** Fraction of lineHeightRatio that is ascent (above baseline). */
    public final float ascentFraction;

    private FontScriptMetrics(float supScale, float supOffset, float subScale, float subOffset,
                              float winAscentRatio, float winDescentRatio) {
        superscriptScale = supScale; superscriptOffset = supOffset;
        subscriptScale = subScale; subscriptOffset = subOffset;
        lineHeightRatio = winAscentRatio + winDescentRatio;
        ascentFraction = lineHeightRatio > 0 ? winAscentRatio / lineHeightRatio : 0.8f;
    }

    public float scale(boolean superscript) {
        return superscript ? superscriptScale : subscriptScale;
    }

    public float offset(boolean superscript) {
        return superscript ? -superscriptOffset : subscriptOffset;
    }

    /** Reads the first face of a TTF/OTF/TTC stream. */
    public static FontScriptMetrics read(InputStream source) throws IOException {
        byte[] data = readAll(source);
        if (data.length < 12) throw new IOException("Font too small");
        int sfnt = 0;
        int magic = u32(data, 0);
        if (magic == 0x74746366) {
            if (data.length < 16) throw new IOException("Truncated TTC");
            int fonts = u32(data, 8);
            if (fonts < 1 || fonts > 64 || 12 + 4 > data.length) throw new IOException("Invalid TTC");
            sfnt = u32(data, 12);
        } else if (magic != 0x00010000 && magic != 0x4f54544f) {
            throw new IOException("Unsupported font container");
        }
        if (sfnt < 0 || sfnt + 12 > data.length) throw new IOException("Invalid sfnt offset");
        int version = u32(data, sfnt);
        if (version != 0x00010000 && version != 0x4f54544f)
            throw new IOException("Unsupported sfnt");
        int count = u16(data, sfnt + 4);
        if (count < 1 || count > 256) throw new IOException("Invalid font table count");
        int headOffset = -1, os2Offset = -1, headLength = 0, os2Length = 0;
        int cursor = sfnt + 12;
        for (int i = 0; i < count; i++) {
            if (cursor + 16 > data.length) throw new IOException("Truncated table directory");
            int tag = u32(data, cursor);
            int offset = u32(data, cursor + 8);
            int length = u32(data, cursor + 12);
            if (tag == 0x68656164) { headOffset = offset; headLength = length; }
            if (tag == 0x4f532f32) { os2Offset = offset; os2Length = length; }
            cursor += 16;
        }
        if (headOffset < 0 || os2Offset < 0 || headLength < 20 || os2Length < 26
                || headOffset + 20 > data.length || os2Offset + 26 > data.length)
            throw new IOException("Invalid head/OS2 tables");
        int units = u16(data, headOffset + 18);
        if (units < 16 || units > 16384) throw new IOException("Invalid unitsPerEm");
        int ySubscriptXSize = s16(data, os2Offset + 10);
        int ySubscriptYSize = s16(data, os2Offset + 12);
        int ySubscriptXOffset = s16(data, os2Offset + 14);
        int ySubscriptYOffset = s16(data, os2Offset + 16);
        int ySuperscriptXSize = s16(data, os2Offset + 18);
        int ySuperscriptYSize = s16(data, os2Offset + 20);
        int ySuperscriptXOffset = s16(data, os2Offset + 22);
        int ySuperscriptYOffset = s16(data, os2Offset + 24);
        float subScale = ySubscriptYSize / (float) units, supScale = ySuperscriptYSize / (float) units;
        float subOffset = ySubscriptYOffset / (float) units, supOffset = ySuperscriptYOffset / (float) units;
        // Read winAscent (offset 74) and winDescent (offset 76) for Word line height
        int winAscent = 0, winDescent = 0;
        if (os2Offset + 78 <= data.length) {
            winAscent = u16(data, os2Offset + 74);
            winDescent = u16(data, os2Offset + 76);
        }
        float winAscRatio = winAscent > 0 ? winAscent / (float) units : 0.8f;
        float winDescRatio = winDescent > 0 ? winDescent / (float) units : 0.2f;
        if (subScale <= 0 || subScale > 1 || supScale <= 0 || supScale > 1
                || subOffset < 0 || subOffset > 1 || supOffset < 0 || supOffset > 1)
            return new FontScriptMetrics(.65f, .30f, .65f, .15f, winAscRatio, winDescRatio);
        return new FontScriptMetrics(supScale, supOffset, subScale, subOffset, winAscRatio, winDescRatio);
    }

    private static byte[] readAll(InputStream source) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n, total = 0;
        while ((n = source.read(buffer)) != -1) {
            total += n;
            if (total > MAX_BYTES) throw new IOException("Font too large");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static int u16(byte[] data, int at) throws IOException {
        if (at + 2 > data.length) throw new EOFException();
        return ((data[at] & 0xff) << 8) | (data[at + 1] & 0xff);
    }

    private static int s16(byte[] data, int at) throws IOException {
        int value = u16(data, at);
        return (value & 0x8000) != 0 ? value - 0x10000 : value;
    }

    private static int u32(byte[] data, int at) throws IOException {
        if (at + 4 > data.length) throw new EOFException();
        return ((data[at] & 0xff) << 24) | ((data[at + 1] & 0xff) << 16)
                | ((data[at + 2] & 0xff) << 8) | (data[at + 3] & 0xff);
    }

    public static int unicodeScript(char c) {
        if (c >= '\u2080' && c <= '\u2089') return -1;
        if (c == '\u00b9' || c == '\u00b2' || c == '\u00b3'
                || c == '\u2070' || (c >= '\u2074' && c <= '\u2079')) return 1;
        return 0;
    }

    public static String plainDigits(CharSequence text, int start, int end) {
        StringBuilder out = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c >= '\u2080' && c <= '\u2089') c = (char) ('0' + c - '\u2080');
            else if (c == '\u00b9') c = '1';
            else if (c == '\u00b2') c = '2';
            else if (c == '\u00b3') c = '3';
            else if (c == '\u2070') c = '0';
            else if (c >= '\u2074' && c <= '\u2079') c = (char) ('4' + c - '\u2074');
            out.append(c);
        }
        return out.toString();
    }
}

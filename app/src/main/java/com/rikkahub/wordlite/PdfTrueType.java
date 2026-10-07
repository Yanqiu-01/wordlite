package com.rikkahub.wordlite;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;
import java.util.HashMap;

/** Bounded sfnt cmap/hmtx reader for the already licensed/subsetted asset fonts. */
public final class PdfTrueType {
    public final byte[] data;
    public final int units;
    public final int glyphCount;
    public final int ascent, descent, xMin, yMin, xMax, yMax;
    private final int cmap, cmapFormat, hmtx, metrics;
    private final TreeMap<Integer, String> unicode = new TreeMap<Integer, String>();
    private final TreeMap<Integer, Integer> used = new TreeMap<Integer, Integer>();
    private int type0, cid, descriptor, program, toUnicode;
    public final String resource;
    private final String baseName;

    public PdfTrueType(byte[] bytes, String key, String resource) throws IOException {
        this.data = bytes; this.resource = resource;
        int sfnt = i32(0) == 0x74746366 ? i32(12) : 0;
        int count = u16(sfnt + 4);
        if (count < 1 || count > 256) throw new IOException("字体表无效");
        HashMap<String, Integer> tables = new HashMap<String, Integer>();
        for (int i = 0; i < count; i++) {
            int at = sfnt + 12 + i * 16, offset = i32(at + 8), size = i32(at + 12);
            if (offset < 0 || size < 0 || offset > data.length - size) throw new IOException("字体表越界");
            tables.put(new String(data, at, 4, java.nio.charset.StandardCharsets.ISO_8859_1), offset);
        }
        if (!tables.containsKey("glyf")) throw new IOException("仅支持 TrueType 轮廓字体嵌入");
        int head = table(tables, "head"), hhea = table(tables, "hhea"), maxp = table(tables, "maxp");
        units = u16(head + 18); if (units < 16) throw new IOException("字体单位无效");
        ascent = s16(hhea + 4); descent = s16(hhea + 6);
        xMin = s16(head + 36); yMin = s16(head + 38); xMax = s16(head + 40); yMax = s16(head + 42);
        glyphCount = u16(maxp + 4); metrics = u16(hhea + 34); hmtx = table(tables, "hmtx");
        int cm = table(tables, "cmap"), selected = -1, format = -1, score = -1;
        for (int i = 0; i < u16(cm + 2); i++) {
            int at = cm + 4 + 8 * i, platform = u16(at), encoding = u16(at + 2), offset = cm + i32(at + 4);
            int kind = u16(offset);
            if (kind != 4 && kind != 12) continue;
            int priority = (platform == 3 ? 2 : platform == 0 ? 1 : 0) + (kind == 12 ? 4 : 0);
            if (priority > score) { selected = offset; format = kind; score = priority; }
        }
        if (selected < 0) throw new IOException("字体 Unicode 映射缺失");
        cmap = selected; cmapFormat = format;
        baseName = "WordLite" + key.replaceAll("[^A-Za-z0-9]", "");
    }
    private int table(Map<String, Integer> tables, String name) throws IOException {
        Integer at = tables.get(name); if (at == null) throw new IOException("字体表缺失: " + name); return at;
    }
    public int glyph(int codePoint) throws IOException {
        if (cmapFormat == 12) {
            int lo = 0, hi = i32(cmap + 12) - 1;
            if (hi < 0 || hi > data.length / 12) throw new IOException("字体映射无效");
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1, at = cmap + 16 + mid * 12;
                int start = i32(at), end = i32(at + 4);
                if (codePoint < start) hi = mid - 1;
                else if (codePoint > end) lo = mid + 1;
                else return i32(at + 8) + codePoint - start;
            }
            return 0;
        }
        if (codePoint > 0xFFFF) return 0;
        int segments = u16(cmap + 6) / 2;
        int end = cmap + 14, start = end + 2 * segments + 2, delta = start + 2 * segments, range = delta + 2 * segments;
        for (int i = 0; i < segments; i++) {
            if (codePoint > u16(end + i * 2)) continue;
            if (codePoint < u16(start + i * 2)) return 0;
            int offset = u16(range + i * 2);
            if (offset == 0) return (codePoint + s16(delta + i * 2)) & 0xFFFF;
            int gid = u16(range + i * 2 + offset + (codePoint - u16(start + i * 2)) * 2);
            return gid == 0 ? 0 : (gid + s16(delta + i * 2)) & 0xFFFF;
        }
        return 0;
    }
    public void use(int glyph, String text) throws IOException {
        if (glyph < 0 || glyph >= glyphCount) throw new IOException("字体字形无效");
        used.put(glyph, Math.round(u16(hmtx + Math.min(glyph, metrics - 1) * 4) * 1000f / units));
        if (text != null && !text.isEmpty() && !unicode.containsKey(glyph)) unicode.put(glyph, text);
    }
    public int reserve(PdfFile pdf) {
        if (type0 == 0) {
            type0 = pdf.reserve(); cid = pdf.reserve(); descriptor = pdf.reserve(); program = pdf.reserve(); toUnicode = pdf.reserve();
        }
        return type0;
    }
    public void finish(PdfFile pdf) throws IOException {
        reserve(pdf);
        pdf.stream(program, "/Length1 " + data.length, data, true);
        int[] box = {xMin, yMin, xMax, yMax};
        StringBuilder bounds = new StringBuilder("["); for (int value : box) bounds.append(Math.round(value * 1000f / units)).append(' '); bounds.append(']');
        pdf.set(descriptor, "<< /Type /FontDescriptor /FontName /" + baseName
                + " /Flags 32 /FontBBox " + bounds + " /ItalicAngle 0 /Ascent " + Math.round(ascent * 1000f / units)
                + " /Descent " + Math.round(descent * 1000f / units) + " /CapHeight " + Math.round(ascent * 1000f / units)
                + " /StemV 80 /FontFile2 " + program + " 0 R >>");
        StringBuilder widths = new StringBuilder("[");
        for (Map.Entry<Integer, Integer> entry : used.entrySet()) widths.append(entry.getKey()).append(" [").append(entry.getValue()).append("] ");
        widths.append(']');
        pdf.set(cid, "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /" + baseName
                + " /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /FontDescriptor "
                + descriptor + " 0 R /CIDToGIDMap /Identity /DW 1000 /W " + widths + " >>");
        StringBuilder mapping = new StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n/CMapName /Adobe-Identity-UCS def\n"
                + "/CMapType 2 def\n1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");
        int batch = 0;
        for (Map.Entry<Integer, String> entry : unicode.entrySet()) {
            if (batch % 100 == 0) mapping.append(Math.min(100, unicode.size() - batch)).append(" beginbfchar\n");
            mapping.append(String.format(java.util.Locale.US, "<%04X> <", entry.getKey()));
            for (byte value : entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_16BE))
                mapping.append(String.format(java.util.Locale.US, "%02X", value & 255));
            mapping.append(">\n"); batch++;
            if (batch % 100 == 0 || batch == unicode.size()) mapping.append("endbfchar\n");
        }
        mapping.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
        pdf.stream(toUnicode, "", mapping.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), true);
        pdf.set(type0, "<< /Type /Font /Subtype /Type0 /BaseFont /" + baseName
                + " /Encoding /Identity-H /DescendantFonts [" + cid + " 0 R] /ToUnicode " + toUnicode + " 0 R >>");
    }
    private int u16(int at) throws IOException {
        if (at < 0 || at > data.length - 2) throw new IOException("字体数据越界");
        return (data[at] & 255) * 256 + (data[at + 1] & 255);
    }
    private int s16(int at) throws IOException { return (short) u16(at); }
    private int i32(int at) throws IOException { return u16(at) << 16 | u16(at + 2); }
}

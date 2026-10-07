package com.rikkahub.wordlite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.zip.DeflaterOutputStream;

/** Small classic PDF object writer: vector pages, embedded TrueType, no native PDF dependency. */
public final class PdfFile {
    private final ArrayList<byte[]> objects = new ArrayList<byte[]>();
    public PdfFile() { objects.add(null); }
    public int reserve() { objects.add(null); return objects.size() - 1; }
    public int add(String value) { int id = reserve(); set(id, value); return id; }
    public void set(int id, String value) { objects.set(id, value.getBytes(StandardCharsets.ISO_8859_1)); }
    public void stream(int id, String dictionary, byte[] data, boolean compress) throws IOException {
        byte[] bytes = data;
        if (compress) {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) { deflater.write(data); }
            bytes = compressed.toByteArray(); dictionary += " /Filter /FlateDecode";
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream(bytes.length + 100);
        result.write(("<< " + dictionary + " /Length " + bytes.length + " >>\nstream\n").getBytes(StandardCharsets.ISO_8859_1));
        result.write(bytes); result.write("\nendstream".getBytes(StandardCharsets.ISO_8859_1));
        objects.set(id, result.toByteArray());
    }
    public byte[] finish(int root) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write("%PDF-1.7\n%\u00E2\u00E3\u00CF\u00D3\n".getBytes(StandardCharsets.ISO_8859_1));
        int[] offsets = new int[objects.size()];
        for (int i = 1; i < objects.size(); i++) {
            if (objects.get(i) == null) throw new IOException("PDF对象未完成");
            offsets[i] = result.size(); result.write((i + " 0 obj\n").getBytes(StandardCharsets.ISO_8859_1));
            result.write(objects.get(i)); result.write("\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        int xref = result.size();
        result.write(("xref\n0 " + objects.size() + "\n0000000000 65535 f \n").getBytes(StandardCharsets.ISO_8859_1));
        for (int i = 1; i < objects.size(); i++) result.write(String.format(java.util.Locale.US,
                "%010d 00000 n \n", offsets[i]).getBytes(StandardCharsets.ISO_8859_1));
        result.write(("trailer\n<< /Size " + objects.size() + " /Root " + root + " 0 R >>\nstartxref\n"
                + xref + "\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1));
        return result.toByteArray();
    }
    public static String number(float value) {
        if ((Float.isNaN(value) || Float.isInfinite(value))) value = 0;
        return String.format(java.util.Locale.US, "%.4f", value);
    }
}

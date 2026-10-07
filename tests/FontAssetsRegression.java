package com.rikkahub.wordlite;

import java.awt.Font;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Verifies all bundled fonts parse correctly and aliases resolve. */
public final class FontAssetsRegression {
    private static int checks;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        File apkPath = new File(args[1]);
        try (ZipFile apk = new ZipFile(apkPath)) {
            Set<String> apkFonts = new HashSet<String>();
            for (java.util.Enumeration<? extends ZipEntry> e = apk.entries(); e.hasMoreElements();) {
                String name = e.nextElement().getName();
                if (name.startsWith("assets/fonts/")) apkFonts.add(name.substring("assets/".length()));
            }
            check(apkFonts.equals(new HashSet<String>(Arrays.asList(DocxFontAssets.PATHS))),
                    "APK contains exactly the " + DocxFontAssets.PATHS.length + " bundled office fonts");
            for (String path : DocxFontAssets.PATHS) {
                byte[] asset;
                try (InputStream in = new FileInputStream(new File(root, "app/src/main/assets/" + path))) {
                    asset = read(in);
                }
                byte[] packed;
                try (InputStream in = apk.getInputStream(apk.getEntry("assets/" + path))) {
                    packed = read(in);
                }
                check(Arrays.equals(asset, packed), path + ": asset and signed APK are byte-identical");
                // TTC files need special handling for AWT
                if (path.endsWith(".ttc")) {
                    check(asset.length > 100000, path + ": TTC file has substantial size");
                    FontScriptMetrics m = FontScriptMetrics.read(new ByteArrayInputStream(asset));
                    check(m.scale(true) > 0 && m.scale(true) <= 1, path + ": TTC script metrics readable");
                } else {
                    Font font = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(asset));
                    check(font.canDisplay('A') && font.canDisplay('3'), path + ": font parser accepts basic glyphs");
                    FontScriptMetrics m = FontScriptMetrics.read(new ByteArrayInputStream(asset));
                    check(m.scale(true) > 0 && m.scale(true) <= 1 && m.offset(true) <= 0
                                    && m.scale(false) > 0 && m.scale(false) <= 1,
                            path + ": script size/offset parameters are valid");
                }
                System.out.println("FONT " + path + " SHA256 " + hash(asset).substring(0, 16) + "...");
            }
        }
        // Alias resolution
        check(DocxFontAssets.TIMES.equals(DocxFontAssets.pathFor("Times New Roman")),
                "Times New Roman resolves to bundled file");
        check(DocxFontAssets.SONG.equals(DocxFontAssets.pathFor("\u5b8b\u4f53"))
                        && DocxFontAssets.SONG.equals(DocxFontAssets.pathFor("SimSun")),
                "Song aliases resolve correctly");
        check(DocxFontAssets.HEI.equals(DocxFontAssets.pathFor("SimHei"))
                        && DocxFontAssets.KAI.equals(DocxFontAssets.pathFor("\u6977\u4f53")),
                "Hei and Kai aliases resolve correctly");
        check(DocxFontAssets.FZ_SMALL_SONG.equals(DocxFontAssets.pathFor("FZDocXiaoBiaoSong")),
                "Founder Small Song resolves independently");
        check(DocxFontAssets.SONG.equals(DocxFontAssets.pathFor("\u534e\u6587\u65b0\u9b4f"))
                        && DocxFontAssets.ARIAL.equals(DocxFontAssets.pathFor("Arial"))
                        && DocxFontAssets.SONG.equals(DocxFontAssets.pathFor("MS Mincho"))
                        && DocxFontAssets.SONG.equals(DocxFontAssets.pathFor("\uff2d\uff33 \u660e\u671d")),
                "removed decorative families have explicit safe fallbacks");
        check(DocxFontAssets.pathFor("NonexistentFont123") == null
                        && DocxFontAssets.pathFor("") == null,
                "truly unknown fonts correctly return null");
        check(FontScriptMetrics.unicodeScript('\u2083') == -1
                        && FontScriptMetrics.unicodeScript('\u00b2') == 1
                        && FontScriptMetrics.unicodeScript('3') == 0,
                "Unicode script digits distinguished from ordinary digits");
        boolean rejected = false;
        try { FontScriptMetrics.read(new ByteArrayInputStream(new byte[32])); }
        catch (java.io.IOException expected) { rejected = true; }
        check(rejected, "invalid font input fails closed");
        System.out.println("SUMMARY " + checks + " font-file assertions passed; Android pixels NOT tested");
    }

    private static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static String hash(byte[] data) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder out = new StringBuilder();
        for (byte b : h) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}

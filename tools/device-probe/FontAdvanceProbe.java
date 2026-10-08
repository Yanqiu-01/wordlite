// Build and run (not part of the APK; runs under app_process like DeviceCapture):
//   javac -nowarn -encoding UTF-8 -classpath tools/android-35.jar -d <out> (Get-ChildItem -Recurse app/src/main/java -Filter *.java) tools/device-probe/FontAdvanceProbe.java
//   java -cp tools/d8.jar com.android.tools.r8.D8 --min-api 23 --lib tools/android-35.jar --release --output <out> <jar-of-<out>>
//   adb -s <serial> push <out>/classes.dex /data/local/tmp/wlcapture/fap.dex
//   adb -s <serial> shell "CLASSPATH=/data/local/tmp/wlcapture/fap.dex app_process -Xmx256m / FontAdvanceProbe /data/local/tmp/wlcapture/wordlite-assets.zip /data/local/tmp/wlcapture/font-advance.txt"
// The assets zip is the one tools/capture-device.ps1 pushes (bundled fonts under assets/fonts/).
// What the numbers mean: docs/layout-parity-target.md section 21.

import android.content.Context;
import com.rikkahub.wordlite.*;
import android.content.res.AssetManager;
import android.graphics.Paint;
import android.graphics.Typeface;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Device-side advance probe for the superscript-unit characters (U+207B / U+00B9 / U+00B7 / U+2103).
 *
 * Question: when a Song-typed run in the thesis holds W + U+00B7 + m + U+207B, which typeface does
 * our engine put on that character and how many document pixels does the phone charge? Word's
 * exported PDF says Times New Roman, 4.056 pt at 12 pt = 5.408 px (tools/pdf-line-truth.py, page 19).
 *
 * args: <assetsZip> <outFile>
 */
public final class FontAdvanceProbe {
    private static PrintWriter out;

    public static void main(String[] args) throws Exception {
        String assetsZip = args[0];
        File outFile = new File(args[1]);
        outFile.getParentFile().mkdirs();
        exemptHiddenApi();
        Class.forName("android.graphics.Typeface");
        AssetManager assets = assetManager(assetsZip);
        DocxTextLayout.initialize(new ProbeContext(assets));
        out = new PrintWriter(new FileWriter(outFile));
        float px = PageGeometry.points(12f);
        say("text_size_12pt_px=" + px);

        say("symbolFamily(declared=Times New Roman, U+207B) -> "
                + FontManager.symbolFamily("Times New Roman", 0x207B, Typeface.NORMAL));
        say("symbolFamily(declared=Song, U+207B) -> "
                + FontManager.symbolFamily("\u5b8b\u4f53", 0x207B, Typeface.NORMAL));
        say("symbolFamily(declared=Times New Roman, U+00B9) -> "
                + FontManager.symbolFamily("Times New Roman", 0x00B9, Typeface.NORMAL));

        Typeface timesFace = DocxTextLayout.resolve("Times New Roman");
        say("DIAG loadedCount=" + FontManager.loadedCount()
                + " pathFor(Times)=" + DocxFontAssets.pathFor("Times New Roman", Typeface.NORMAL)
                + " loaded=" + (FontManager.load(DocxFontAssets.TIMES) != null)
                + " resolve==load(TIMES)=" + (timesFace == FontManager.load(DocxFontAssets.TIMES)));
        for (int mode = 0; mode < 4; mode++) {
            Paint q = new Paint();
            q.setTextSize(px);
            q.setTypeface(FontManager.load(DocxFontAssets.TIMES));
            q.setSubpixelText((mode & 1) == 1);
            q.setAntiAlias((mode & 2) == 2);
            say("DIAG subpixel=" + ((mode & 1) == 1) + " aa=" + ((mode & 2) == 2)
                    + " WWWW=" + fmt(q.measureText("WWWW")) + " mw=" + fmt(q.measureText("mw"))
                    + " supminus=" + fmt(q.measureText("\u207b")));
        }

        Paint pTimes = paint(timesFace, px);
        Paint pSong = paint(DocxTextLayout.resolve("\u5b8b\u4f53"), px);
        Paint pStix = paint(DocxTextLayout.resolve("STIX Two Math"), px);
        Paint pSystem = paint(Typeface.SERIF, px);
        String[] probes = { "\u207b", "\u00b9", "\u00b7", "\u2103", "m", "K", "\u4e2d" };
        String[] names = { "U+207B sup-minus ", "U+00B9 sup-1   ", "U+00B7 mid-dot ", "U+2103 celsius",
                           "U+006D m       ", "U+004B K       ", "U+4E2D han     " };
        String[] hmtx = { "5.414", "4.797", "5.328", "16.383", "12.445", "11.555", "16.000" };
        for (int i = 0; i < probes.length; i++) {
            say(String.format("%s px: times=%-7s song=%-7s stix=%-7s system_serif=%-7s hmtx(Times)=%s",
                    names[i], fmt(adv(pTimes, probes[i])), fmt(adv(pSong, probes[i])),
                    fmt(adv(pStix, probes[i])), fmt(adv(pSystem, probes[i])), hmtx[i]));
        }

        StringBuilder ms = new StringBuilder();
        StringBuilder ws = new StringBuilder();
        StringBuilder ss = new StringBuilder();
        StringBuilder han = new StringBuilder();
        for (int i = 0; i < 20; i++) { ms.append('m'); ws.append('W'); ss.append("\u207b"); han.append('\u4e2d'); }
        say("ROUNDING times m x1=" + fmt(adv(pTimes, "m")) + " m x20=" + fmt(adv(pTimes, ms.toString()))
                + " (hmtx exact 248.90) W x20=" + fmt(adv(pTimes, ws.toString()))
                + " supminus x1=" + fmt(adv(pTimes, "\u207b")) + " x20=" + fmt(adv(pTimes, ss.toString()))
                + " (hmtx exact 108.28)");
        say("ROUNDING song han x1=" + fmt(adv(pSong, "\u4e2d")) + " han x20=" + fmt(adv(pSong, han.toString()))
                + " (hmtx exact 320)");

        String unit = "102.6W\u00b7m\u207b\u00b9\u00b7K\u207b\u00b9";
        say("unit string \"" + unit + "\" natural width on phone=" + fmt(adv(pTimes, unit))
                + " px");
        say("script metrics Times: scale=" + FontManager.metrics("Times New Roman").superscriptScale
                + " offset=" + FontManager.metrics("Times New Roman").superscriptOffset
                + " -> U+207B is billed UNSCALED (unicodeScript('" + "\u207b" + "')="
                + FontScriptMetrics.unicodeScript('\u207b') + ")");
        out.close();
        System.out.println("wrote " + outFile);
    }

    private static float adv(Paint p, String s) { return p.measureText(s); }

    private static Paint paint(Typeface face, float px) {
        Paint p = new Paint();
        p.setAntiAlias(false);
        p.setSubpixelText(true);
        p.setTextSize(px);
        p.setTypeface(face);
        return p;
    }

    private static String fmt(float v) { return String.format("%.3f", v); }

    private static void say(String s) { System.out.println(s); out.println(s); out.flush(); }

    private static void exemptHiddenApi() {
        try {
            Class<?> vm = Class.forName("dalvik.system.VMRuntime");
            Object runtime = vm.getDeclaredMethod("getRuntime").invoke(null);
            vm.getDeclaredMethod("setHiddenApiExemptions", String[].class)
                    .invoke(runtime, new Object[] { new String[] { "L" } });
        } catch (Throwable ignored) { }
    }

    private static AssetManager assetManager(String zip) throws Exception {
        Constructor<AssetManager> ctor = AssetManager.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        AssetManager am = ctor.newInstance();
        Method add = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        add.setAccessible(true);
        add.invoke(am, zip);
        return am;
    }

    private static final class ProbeContext extends android.content.ContextWrapper {
        private final AssetManager assets;
        ProbeContext(AssetManager assets) { super(null); this.assets = assets; }
        @Override public AssetManager getAssets() { return assets; }
        @Override public Context getApplicationContext() { return this; }
    }
}


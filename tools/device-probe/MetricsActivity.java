package com.rikkahub.wordlite.metrics;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.Log;

import com.rikkahub.wordlite.DocxTextLayout;
import com.rikkahub.wordlite.FontManager;
import com.rikkahub.wordlite.PageGeometry;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

/**
 * Runs the same advance measurements the app_process probe runs, but inside a real zygote-forked
 * application process with a real APK asset manager. Purpose: decide whether the integer advance
 * snapping seen under app_process (docs/layout-parity-target.md 21.4) is a harness artefact or the
 * same thing the installed Word Lite does on this phone.
 *
 * Readings taken by this activity on EAMUT20528011355 (CDY-AN90, Android 10, API 29), 2026-10-09:
 *   default paint (anti+subpixel, hinting=NORMAL): m=12.000, m x20=240.000, WWWW=60.000, U+207B=5.000
 *   setHinting(HINTING_OFF):                       m=12.000, m x20=240.000 -- hinting is not the lever
 *   StaticLayout m x40, default:                   lineWidth=480.000
 *   StaticLayout m x40, HINTING_OFF:               lineWidth=480.000
 *   StaticLayout m x40, setLinearText(true):       lineWidth=497.813 = exact hmtx sum = the number Word bills
 * setLinearText(true) is the only lever found; the app_process probe reads the same integers, so the
 * harness is not what rounds. See docs/layout-parity-target.md 23.1 and 23.2.
 *
 * It is a separate package (com.rikkahub.wordlite.metrics) so it never touches the installed app,
 * and it builds with tools/build-metrics-probe.ps1.
 */
public final class MetricsActivity extends Activity {
    private static final String TAG = "WLmetrics";
    private final StringBuilder report = new StringBuilder();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            run();
        } catch (Throwable t) {
            line("FAILED " + t);
        }
        write();
        finish();
    }

    private void run() throws Exception {
        float px = PageGeometry.points(12f);
        line("model=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT
                + " densityDpi=" + getResources().getConfiguration().densityDpi
                + " fontScale=" + getResources().getConfiguration().fontScale
                + " text_size_12pt=" + px);

        // (a) straight from the APK asset, the way FontManager loads it
        Typeface fromAsset = Typeface.createFromAsset(getAssets(), "fonts/times-new-roman.ttf");
        line("createFromAsset(times-new-roman.ttf)=" + (fromAsset != null));

        // (b) the engine's own face for the family name the document declares
        DocxTextLayout.initialize(this);
        Typeface engineFace = DocxTextLayout.resolve("Times New Roman");
        line("DocxTextLayout.resolve(Times New Roman)==createFromAsset: "
                + (engineFace == fromAsset) + " loadedCount=" + FontManager.loadedCount());

        String[] probes = { "m", "\u207b", "I", "WWWW", "\u4e2d" };
        String[] labels = { "m", "U+207B", "I", "WWWW", "han" };
        StringBuilder row = new StringBuilder("DEFAULT_PAINT(anti+subpixel) ");
        TextPaint base = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        base.setTextSize(px);
        base.setTypeface(engineFace);
        for (int i = 0; i < probes.length; i++) row.append(labels[i]).append("=").append(f(base, probes[i])).append(" ");
        row.append("hinting=").append(base.getHinting());
        line(row.toString());

        TextPaint off = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        off.setTextSize(px);
        off.setTypeface(engineFace);
        off.setHinting(Paint.HINTING_OFF);
        line("HINTING_OFF " + f(off, "m") + " m x20=" + f(off, rep("m", 20)) + " U+207B=" + f(off, "\u207b"));

        TextPaint lin = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        lin.setTextSize(px);
        lin.setTypeface(engineFace);
        lin.setLinearText(true);
        line("LINEARTEXT true " + f(lin, "m") + " m x20=" + f(lin, rep("m", 20)) + " U+207B=" + f(lin, "\u207b"));

        // (c) the same through StaticLayout, which is what actually breaks lines
        String s40 = rep("m", 40);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        tp.setTextSize(px);
        tp.setTypeface(engineFace);
        int defaultHinting = tp.getHinting();
        StaticLayout sl = StaticLayout.Builder.obtain(s40, 0, s40.length(), tp, 20000).build();
        line("STATICLAYOUT default hinting m x40 lineWidth=" + f2(sl.getLineWidth(0))
                + " (hmtx exact " + f2(40 * 12.4453125f) + ")");
        tp.setHinting(Paint.HINTING_OFF);
        StaticLayout slOff = StaticLayout.Builder.obtain(s40, 0, s40.length(), tp, 20000).build();
        line("STATICLAYOUT hinting-off m x40 lineWidth=" + f2(slOff.getLineWidth(0))
                + " (turning hinting off changes nothing)");
        tp.setHinting(defaultHinting);
        tp.setLinearText(true);
        StaticLayout sl2 = StaticLayout.Builder.obtain(s40, 0, s40.length(), tp, 20000).build();
        line("STATICLAYOUT linearText m x40 lineWidth=" + f2(sl2.getLineWidth(0)));

        // (d) the real string from the thesis line on page 19
        String unit = "102.6W\u00b7m\u207b\u00b9\u00b7K\u207b\u00b9";
        TextPaint tp3 = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        tp3.setTextSize(px);
        tp3.setTypeface(engineFace);
        line("unit string natural=" + f(tp3, unit));
        line("harness reference (app_process, same phone): m=12.000 m x20=240.000 U+207B=5.000"
                + " WWWW=60.000 han=16.000 linearText m x20=249.000");
    }

    private String f(Paint p, String s) { return f2(p.measureText(s)); }

    private String f2(float v) { return String.format("%.3f", v); }

    private static String rep(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    private void line(String s) {
        report.append(s).append("\n");
        Log.i(TAG, s);
    }

    private void write() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            File out = new File(dir, "metrics.txt");
            try (PrintWriter w = new PrintWriter(new FileWriter(out))) { w.print(report); }
            Log.i(TAG, "wrote " + out.getAbsolutePath());
        } catch (Throwable t) {
            Log.i(TAG, "write failed " + t);
        }
    }
}
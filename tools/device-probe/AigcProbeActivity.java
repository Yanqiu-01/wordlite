package com.rikkahub.wordlite;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;

/**
 * On-device AIGC probe. Loads the criteria table that ships inside the APK, runs the very same
 * {@link AigcDetector#detect} the editor runs, and logs the four readouts the user sees.
 * The host JVM suites cannot cover this: they have no assets, so the table never loads there.
 * Separate applicationId, so it never replaces the installed Word Lite.
 * Built by tools/build-aigc-probe.ps1.
 */
public final class AigcProbeActivity extends Activity {
    private static final String TAG = "WLaigc";
    private static final String REPORT = "aigc-probe-report.txt";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(new Runnable() {
            @Override public void run() { measure(); }
        }).start();
    }

    private final StringBuilder report = new StringBuilder();

    /** logcat 在华为 ROM 上会吞行，所以同一份读数也写进应用自己的目录，脚本再 pull 回来。 */
    private void say(String line) {
        Log.i(TAG, line);
        report.append(line).append("\n");
    }

    private void measure() {
        try {
            load("AigcRhythm", "rhythm");
            load("AigcTells", "tells");
            load("AigcBaseline", "baseline");
            say("ENGAGED " + AigcRhythm.engaged() + " VERSION [" + AigcRhythm.version() + "]");
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(getAssets().open(AigcRhythm.FILE), "UTF-8"));
                say("ASSET " + AigcRhythm.FILE + " 第一行 " + br.readLine() + " 第二行 " + br.readLine());
                br.close();
            } catch (Throwable t) {
                say("ASSET 打不开 " + t);
            }
            File dir = getExternalFilesDir(null);
            for (int i = 0; i < 8; i++) {
                String name = getIntent().getStringExtra(i == 0 ? "file" : "file" + (i + 1));
                if (name == null || name.trim().isEmpty()) continue;
                String text = read(new File(dir, name.trim()));
                if (text == null) { say("MISSING " + name); continue; }
                AigcDetector.Result r = AigcDetector.detect(text);
                say("NAME " + name + " \u8bfb\u53d6 " + text.length() + " \u5b57\u7b26\uff0c\u53c2\u4e0e\u5224\u5b9a "
                        + r.comparedChars + " \u5b57");
                say("INDEX " + AigcDetector.indexText(r.rate));
                say("SCALE " + AigcDetector.scaleLine(r));
                say("FLAGGED " + AigcDetector.flaggedLine(r));
                say("MEASURE " + AigcDetector.measurement(r));
                say("DETAIL \u6863\u4f4d " + r.tier + " \u53ef\u7591\u6bb5 " + r.suspiciousParagraphs.size()
                        + " \u53ef\u7591\u5b57\u6570 " + r.flaggedChars + " \u8fc7\u7ebf\u533a\u95f4 " + r.flaggedSegments
                        + " \u591f\u5224\u5b9a\u7684\u6bb5 " + r.judgeableParagraphs + "/" + r.paragraphsTotal
                        + " \u8282\u594f\u53e5 " + r.rhythmSentences + " \u82f1\u6587\u5b57 " + r.latinChars
                        + " \u5bc6\u5ea6 " + r.densityPer1000 + " \u8bcd\u8868\u547d\u4e2d " + r.tellHits);
                say("CONF " + r.confidence.label() + " [" + r.confidence.use() + "]");
                say("PROVENANCE " + r.provenance);
                say("VERDICT " + r.verdict);
            }
        } catch (Throwable t) {
            say("THROW " + t);
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(6, st.length); i++) say("  at " + st[i]);
        } finally {
            say("DONE");
            try {
                java.io.FileWriter w = new java.io.FileWriter(new File(getExternalFilesDir(null), REPORT));
                try { w.write(report.toString()); } finally { w.close(); }
            } catch (Exception ignored) { }
        }
    }

    /** \u6bcf\u4e2a\u8868\u5355\u72ec\u88c5\uff1a\u4e00\u4e2a\u8868\u628a\u5d29\u4e0d\u80fd\u8ba9\u53e6\u4e24\u5f20\u8868\u4e5f\u6ca1\u6709\u8bfb\u6570\u3002 */
    private void load(String who, String ignoredTag) {
        try {
            if ("rhythm".equals(ignoredTag)) AigcRhythm.loadFromAssets(this);
            else if ("tells".equals(ignoredTag)) AigcTells.loadFromAssets(this);
            else AigcBaseline.loadFromAssets(this);
            say("LOAD " + who + " ok");
        } catch (Throwable t) {
            say("LOAD " + who + " \u5f02\u5e38 " + t);
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(4, st.length); i++) say("  at " + st[i]);
        }
    }
    private static String read(File f) throws Exception {
        if (!f.isFile()) return null;
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), Charset.forName("UTF-8"));
        } finally {
            in.close();
        }
    }
}

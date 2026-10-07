package com.rikkahub.wordlite;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.DisplayMetrics;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Native-graphics render of the redesigned ribbon; PNG is inspected manually, never guessed. */
@RunWith(RobolectricTestRunner.class)
// sdk 28 is the only sdk robolectric.enabledSdks allows, and a class asking for another sdk is silently
// never run. The qualifier pins a phone-like density: the ribbon is sized in dp, so at Robolectric's
// default density of 1.0 it is 89px tall and the PNG is not representative of the device.
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "zh-rCN-w411dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class RibbonRenderTest {
    @Test public void homeAndReviewRibbonsRenderToPng() throws Exception {
        if ("aarch64".equals(System.getProperty("os.arch")))
            org.junit.Assume.assumeTrue("native runtime unsupported on Linux aarch64", false);
        float density = RuntimeEnvironment.getApplication().getResources()
                .getDisplayMetrics().densityDpi / (float) DisplayMetrics.DENSITY_DEFAULT;
        assertTrue("the qualifier has to give a phone-like density", density >= 2f);
        File out = new File(System.getProperty("app.root"), "artifacts/analysis");
        out.mkdirs();
        for (String tab : new String[]{"开始", "审阅"}) {
            RibbonUI ribbon = new RibbonUI(RuntimeEnvironment.getApplication(), "刘启航-20261001-1.docx", id -> { });
            int width = 1080;
            ribbon.select(tab);
            ribbon.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            ribbon.layout(0, 0, width, ribbon.getMeasuredHeight());
            Bitmap bitmap = Bitmap.createBitmap(width, Math.max(1, ribbon.getMeasuredHeight()), Bitmap.Config.ARGB_8888);
            ribbon.draw(new Canvas(bitmap));
            try (FileOutputStream file = new FileOutputStream(new File(out, "ribbon-" + tab + ".png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, file);
            }
            // Tab strip (42dp) + hairline + command row (46dp).
            int expected = Math.round(42 * density) + 1 + Math.round(46 * density);
            assertEquals("ribbon height is the two dp rows for " + tab, expected, bitmap.getHeight());
            // The strip fills its own background, so "not transparent" would pass on an empty
            // bar. Count pixels that actually differ from that background instead.
            int surfaceColour = bitmap.getPixel(2, bitmap.getHeight() - 2);
            int ink = 0;
            for (int y = 0; y < bitmap.getHeight(); y += 2)
                for (int x = 0; x < bitmap.getWidth(); x += 2)
                    if (bitmap.getPixel(x, y) != surfaceColour) ink++;
            System.out.println("RIBBON " + tab + " height=" + bitmap.getHeight()
                    + " density=" + density + " surface=" + Integer.toHexString(surfaceColour)
                    + " marks=" + ink);
            assertTrue("the ribbon draws labels and icons (" + tab + ", marks=" + ink + ")", ink > 2000);
        }
    }
}

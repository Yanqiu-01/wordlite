package com.rikkahub.wordlite;

import android.graphics.Bitmap;
import android.graphics.Canvas;
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
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class RibbonRenderTest {
    @Test public void homeAndReviewRibbonsRenderToPng() throws Exception {
        if ("aarch64".equals(System.getProperty("os.arch")))
            org.junit.Assume.assumeTrue("native runtime unsupported on Linux aarch64", false);
        File out = new File(System.getProperty("app.root"), "artifacts/analysis");
        out.mkdirs();
        for (String tab : new String[]{"开始", "审阅"}) {
            RibbonUI ribbon = new RibbonUI(RuntimeEnvironment.getApplication(), "刘启航-20261001-1.docx", id -> { });
            int width = 1080;
            ribbon.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            ribbon.layout(0, 0, width, ribbon.getMeasuredHeight());
            ribbon.select(tab);
            ribbon.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            ribbon.layout(0, 0, width, ribbon.getMeasuredHeight());
            Bitmap bitmap = Bitmap.createBitmap(width, Math.max(1, ribbon.getMeasuredHeight()), Bitmap.Config.ARGB_8888);
            ribbon.draw(new Canvas(bitmap));
            try (FileOutputStream file = new FileOutputStream(new File(out, "ribbon-" + tab + ".png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, file);
            }
            assertTrue(bitmap.getHeight() > 100);
        }
    }
}

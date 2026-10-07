package com.rikkahub.wordlite;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = {28, 35})
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
public class ViewportTest {
    private ZoomableScrollView viewport;
    private LinearLayout stack;
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        context.getSharedPreferences("wordlite", Context.MODE_PRIVATE).edit().clear().commit();
        viewport = new ZoomableScrollView(context);
        stack = new LinearLayout(context);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setPadding(12, 14, 12, 32);
        DocxDocument document = new DocxDocument();
        PageGeometry geometry = new PageGeometry(document.section);
        for (int i = 0; i < 5; i++) {
            PaperPageView page = new PaperPageView(context, document, new A4Paginator.PageContent(), geometry,
                    i + 1, 5, (index, offset) -> {});
            page.setTag("page-" + i);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = 12;
            stack.addView(page, params);
        }
        viewport.addView(stack, new ViewGroup.LayoutParams(-1, -2));
        measure(390, 600);
    }

    private void measure(int width, int height) {
        viewport.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        viewport.layout(0, 0, width, height);
    }

    private void event(long start, long time, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(start, time, action, x, y, 0);
        viewport.dispatchTouchEvent(e);
        e.recycle();
    }

    private void swipe(float from, float to) {
        long now = SystemClock.uptimeMillis();
        event(now, now, MotionEvent.ACTION_DOWN, 180, from);
        event(now, now + 100, MotionEvent.ACTION_MOVE, 180, to);
        event(now, now + 200, MotionEvent.ACTION_CANCEL, 180, to);
    }

    @Test public void entireDocumentIsMeasuredBeyondViewport() {
        int expected = stack.getPaddingTop() + stack.getPaddingBottom();
        for (int i = 0; i < 5; i++) expected += stack.getChildAt(i).getMeasuredHeight() + 12;
        assertEquals(expected, stack.getMeasuredHeight());
        assertEquals(expected, stack.getHeight());
        assertTrue(stack.getHeight() > 4 * viewport.getHeight());
        assertEquals(600, viewport.getHeight());
    }

    @Test public void swipeCanReachLastPageAndReturnToFirst() {
        for (int i = 0; i < 12; i++) swipe(550, 50);
        assertEquals(600 - stack.getHeight(), stack.getTranslationY(), 1f);
        for (int i = 0; i < 12; i++) swipe(50, 550);
        assertEquals(0f, stack.getTranslationY(), 1f);
    }

    @Test public void jumpAndZoomRetainFullScrollRange() {
        viewport.scrollToPage(3);
        assertEquals(-stack.getChildAt(3).getTop(), stack.getTranslationY(), 1f);
        viewport.zoomBy(2f);
        assertEquals(2f, viewport.scaleFactor(), 0.001f);
        float before = stack.getTranslationY();
        swipe(500, 100);
        assertEquals(before - 400, stack.getTranslationY(), 1f);
        viewport.scrollToPage(4);
        assertEquals(-stack.getChildAt(4).getTop() * 2f, stack.getTranslationY(), 1f);
        viewport.resetScale();
        assertEquals(1f, viewport.scaleFactor(), 0.001f);
        assertEquals(0f, stack.getTranslationY(), 1f);
    }

    @Test public void documentGrowthAfterLoadingUpdatesScrollBounds() {
        stack.removeViews(1, 4);
        measure(390, 600);
        swipe(550, 50);
        assertEquals(0f, stack.getTranslationY(), 1f);
        for (int i = 1; i < 5; i++) {
            View page = new View(context);
            page.setTag("page-" + i);
            stack.addView(page, new LinearLayout.LayoutParams(-1, 550));
        }
        measure(390, 600);
        swipe(550, 50);
        assertEquals(-500f, stack.getTranslationY(), 1f);
    }

    @Test public void pinchCanContinueAsSingleFingerPan() {
        long now = SystemClock.uptimeMillis();
        event(now, now, MotionEvent.ACTION_DOWN, 110, 300);
        multi(now, now + 20, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 110, 280);
        multi(now, now + 80, MotionEvent.ACTION_MOVE, 75, 315);
        multi(now, now + 140, MotionEvent.ACTION_MOVE, 40, 350);
        assertTrue("pinch increased scale", viewport.scaleFactor() > 1.1f);
        multi(now, now + 200, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 40, 350);
        float before = stack.getTranslationY();
        event(now, now + 260, MotionEvent.ACTION_MOVE, 40, 200);
        assertEquals(before - 100f, stack.getTranslationY(), 1f);
        event(now, now + 300, MotionEvent.ACTION_CANCEL, 40, 200);
        before = stack.getTranslationY();
        swipe(500, 100);
        assertEquals(before - 400f, stack.getTranslationY(), 1f);
    }

    private void multi(long start, long time, int action, float x0, float x1) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = i == 0 ? x0 : x1;
            coordinates[i].y = 300;
            coordinates[i].pressure = 1;
            coordinates[i].size = 1;
        }
        MotionEvent e = MotionEvent.obtain(start, time, action, 2, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        viewport.dispatchTouchEvent(e);
        e.recycle();
    }

    @Test public void resizeAndReflowClampPositionInsideDocument() {
        viewport.scrollToPage(4);
        measure(390, 1000);
        assertTrue(stack.getTranslationY() >= 1000 - stack.getHeight());
        stack.removeViews(1, 4);
        measure(390, 1000);
        assertTrue(stack.getTranslationY() >= 0);
    }

    @Test public void homeContainsOnlyDocumentActionsAndRecentFileState() {
        Activity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        List<String> labels = new ArrayList<>();
        collect(activity.findViewById(android.R.id.content), labels);
        assertEquals(Arrays.asList("Word Lite", "打开文档", "最近文件", "暂无文件"), labels);
        TextView open = findLabel(activity.findViewById(android.R.id.content), "打开文档");
        assertNotNull(open);
        open.performClick();
        Intent request = Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.getAction());
    }

    @Test public void flingContinuesWithinBoundsAndNewTouchStopsIt() {
        long now = SystemClock.uptimeMillis();
        event(now, now, MotionEvent.ACTION_DOWN, 180, 550);
        event(now, now + 20, MotionEvent.ACTION_MOVE, 180, 400);
        event(now, now + 40, MotionEvent.ACTION_MOVE, 180, 250);
        event(now, now + 60, MotionEvent.ACTION_UP, 180, 100);
        float released = stack.getTranslationY();
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(100));
        viewport.computeScroll();
        assertTrue("fling continues after release", stack.getTranslationY() < released);
        assertTrue(stack.getTranslationY() >= viewport.getHeight() - stack.getHeight());
        now = SystemClock.uptimeMillis();
        event(now, now, MotionEvent.ACTION_DOWN, 180, 300);
        float stopped = stack.getTranslationY();
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(500));
        viewport.computeScroll();
        assertEquals(stopped, stack.getTranslationY(), 1f);
        event(now, now + 500, MotionEvent.ACTION_CANCEL, 180, 300);
    }

    @Test public void zoomedTapTargetsCorrectPageWithoutTreatingSwipeAsTap() {
        int[] clicked = {-1};
        for (int i = 0; i < 5; i++) {
            final int pageIndex = i;
            stack.getChildAt(i).setOnClickListener(view -> clicked[0] = pageIndex);
        }
        viewport.zoomBy(2f);
        viewport.scrollToPage(2);
        long now = SystemClock.uptimeMillis();
        event(now, now, MotionEvent.ACTION_DOWN, 195, 200);
        event(now, now + 100, MotionEvent.ACTION_UP, 195, 200);
        assertEquals(2, clicked[0]);
        clicked[0] = -1;
        swipe(500, 100);
        assertEquals(-1, clicked[0]);
    }

    @Test public void recentFileCanBeReopened() {
        java.io.File document = new java.io.File(System.getProperty("app.root"), "tests/fixture.docx");
        // Uri.fromFile() on a Windows host produces file://E%3A\path, whose getPath() is not a
        // filesystem path, so openRecent() rightly decides the entry is dead. Android only ever
        // stores absolute POSIX paths, so build the URI in that shape and test the real behaviour.
        String slashPath = document.getAbsolutePath().replace(java.io.File.separatorChar, '/');
        android.net.Uri uri = android.net.Uri.parse("file://"
                + (slashPath.startsWith("/") ? "" : "/") + slashPath);
        context.getSharedPreferences("wordlite", Context.MODE_PRIVATE).edit()
                .putString("last_name", "fixture.docx").putString("last_uri", uri.toString()).commit();
        Activity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        TextView recent = findLabel(activity.findViewById(android.R.id.content), "fixture.docx");
        assertNotNull(recent);
        assertTrue(recent.isEnabled());
        recent.performClick();
        Intent request = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(request);
        assertEquals(uri, request.getData());
        assertEquals(EditorActivity.class.getName(), request.getComponent().getClassName());
    }

    @Test public void unavailableRecentFileReturnsToPicker() {
        context.getSharedPreferences("wordlite", Context.MODE_PRIVATE).edit()
                .putString("last_name", "missing.docx").putString("last_uri", "file:///nonexistent/missing.docx").commit();
        Activity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        TextView recent = findLabel(activity.findViewById(android.R.id.content), "missing.docx");
        assertNotNull(recent);
        recent.performClick();
        assertNotNull(findLabel(activity.findViewById(android.R.id.content), "暂无文件"));
        Intent request = Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.getAction());
    }

    private void collect(View view, List<String> labels) {
        if (view instanceof TextView) labels.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), labels);
        }
    }

    private TextView findLabel(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView result = findLabel(group.getChildAt(i), label);
                if (result != null) return result;
            }
        }
        return null;
    }
}

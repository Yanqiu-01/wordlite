package com.rikkahub.wordlite;

import android.content.Context;
import android.os.SystemClock;
import android.view.VelocityTracker;
import android.widget.OverScroller;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Measures the full document inside a clipped pan-and-zoom viewport. */
public final class ZoomableScrollView extends FrameLayout {
    public interface Listener { void changed(int page, float zoom); }
    private Listener listener;
    public void setListener(Listener value) { listener = value; }
    public int currentPage() {
        if (content == null) return 0;
        float y = -translationY / Math.max(.01f, scale);
        int page = 0;
        if (content instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) content;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getTag() instanceof String && ((String) child.getTag()).startsWith("page-")
                        && child.getTop() <= y + getHeight() / (3f * scale)) page = i;
            }
        }
        return page;
    }
    private final ScaleGestureDetector scaleDetector;
    private final OverScroller scroller;
    private final int touchSlop;
    private final int minFlingVelocity;
    private final int maxFlingVelocity;
    private VelocityTracker velocityTracker;
    private boolean multiTouch;
    private int activePointerId;
    private View content;
    private float scale = 1f;
    private float minScale = 1f;
    private float translationX;
    private float translationY;
    private boolean scaling;
    private boolean panning;
    private float lastX;
    private float lastY;
    private float downX;
    private float downY;

    public ZoomableScrollView(Context context) {
        super(context);
        setClipChildren(true);
        setClipToPadding(true);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        minFlingVelocity = configuration.getScaledMinimumFlingVelocity();
        maxFlingVelocity = configuration.getScaledMaximumFlingVelocity();
        scroller = new OverScroller(context);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                scaling = true;
                multiTouch = true;
                scroller.forceFinished(true);
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                if (!(factor > 0f) || Float.isNaN(factor) || Float.isInfinite(factor)) return true;
                float next = clamp(scale * factor, minScale, 4f);
                if (Math.abs(next - scale) < 0.0005f) return true;
                float focusX = detector.getFocusX();
                float focusY = detector.getFocusY();
                translationX = focusX - (focusX - translationX) * (next / scale);
                translationY = focusY - (focusY - translationY) * (next / scale);
                scale = next;
                clampPan();
                applyTransform();
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector detector) {
                scaling = false;
                clampPan();
                applyTransform();
            }
        });
    }

    @Override public void addView(View child) {
        addView(child, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    @Override public void onViewAdded(View child) {
        super.onViewAdded(child);
        content = child;
        applyTransform();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (content == null) {
            super.onMeasure(widthSpec, heightSpec);
            return;
        }
        MarginLayoutParams params = (MarginLayoutParams) content.getLayoutParams();
        int horizontal = getPaddingLeft() + getPaddingRight() + params.leftMargin + params.rightMargin;
        int vertical = getPaddingTop() + getPaddingBottom() + params.topMargin + params.bottomMargin;
        // WRAP_CONTENT in FrameLayout is otherwise capped at one viewport.
        content.measure(getChildMeasureSpec(widthSpec, horizontal, params.width),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        setMeasuredDimension(resolveSize(Math.max(getSuggestedMinimumWidth(), content.getMeasuredWidth() + horizontal), widthSpec),
                resolveSize(Math.max(getSuggestedMinimumHeight(), content.getMeasuredHeight() + vertical), heightSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        clampPan();
        applyTransform();
    }

    @Override public void computeScroll() {
        if (!scroller.computeScrollOffset()) return;
        translationX = scroller.getCurrX();
        translationY = scroller.getCurrY();
        clampPan();
        applyTransform();
        postInvalidateOnAnimation();
    }

    public float scaleFactor() { return scale; }

    public void zoomBy(float factor) {
        if (content == null || !(factor > 0f) || Float.isInfinite(factor)) return;
        scroller.forceFinished(true);
        float next = clamp(scale * factor, minScale, 4f);
        if (Math.abs(next - scale) < 0.0005f) return;
        float focusX = getWidth() / 2f;
        float focusY = getHeight() / 2f;
        translationX = focusX - (focusX - translationX) * (next / scale);
        translationY = focusY - (focusY - translationY) * (next / scale);
        scale = next;
        clampPan();
        applyTransform();
    }

    public void resetScale() {
        scroller.forceFinished(true);
        scale = minScale;
        translationX = 0f;
        translationY = 0f;
        clampPan();
        applyTransform();
    }

    public void scrollToPage(int index) {
        if (content == null) return;
        View page = content.findViewWithTag("page-" + index);
        if (page == null) return;
        scroller.forceFinished(true);
        translationY = -page.getTop() * scale;
        clampPan();
        applyTransform();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        clampPan();
        applyTransform();
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true);
            recycleVelocityTracker();
            velocityTracker = VelocityTracker.obtain();
            scaling = false;
        }
        if (velocityTracker != null) velocityTracker.addMovement(event);
        scaleDetector.onTouchEvent(event);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                activePointerId = event.getPointerId(0);
                multiTouch = false;
                lastX = downX = event.getX();
                lastY = downY = event.getY();
                panning = false;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouch = true;
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                int remaining = event.getActionIndex() == 0 ? 1 : 0;
                activePointerId = event.getPointerId(remaining);
                lastX = event.getX(remaining);
                lastY = event.getY(remaining);
                panning = true;
                if (velocityTracker != null) velocityTracker.clear();
                return true;
            case MotionEvent.ACTION_MOVE:
                int pointer = event.findPointerIndex(activePointerId);
                if (pointer < 0) return true;
                float x = event.getX(pointer), y = event.getY(pointer);
                float dx = x - lastX, dy = y - lastY;
                if (!panning && Math.hypot(x - downX, y - downY) > touchSlop) panning = true;
                if (panning && !scaling && event.getPointerCount() == 1) {
                    translationX += dx;
                    translationY += dy;
                    clampPan();
                    applyTransform();
                }
                lastX = x;
                lastY = y;
                return true;
            case MotionEvent.ACTION_UP:
                if (!panning && !multiTouch && Math.hypot(event.getX() - downX, event.getY() - downY) <= touchSlop) {
                    dispatchTap(event.getX(), event.getY());
                    performClick();
                } else if (panning) {
                    fling();
                }
                scaling = false;
                panning = false;
                recycleVelocityTracker();
                return true;
            case MotionEvent.ACTION_CANCEL:
                scaling = false;
                panning = false;
                recycleVelocityTracker();
                return true;
            default:
                return true;
        }
    }

    private void dispatchTap(float x, float y) {
        if (content == null) return;
        dispatchPageTap(content, (x - content.getLeft() - translationX) / scale,
                (y - content.getTop() - translationY) / scale);
    }

    private boolean dispatchPageTap(View view, float x, float y) {
        if (view.getVisibility() != View.VISIBLE || x < 0 || y < 0 || x >= view.getWidth() || y >= view.getHeight()) return false;
        if (view instanceof PaperPageView) {
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0);
            view.dispatchTouchEvent(down);
            view.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                if (dispatchPageTap(child, x + group.getScrollX() - child.getLeft(),
                        y + group.getScrollY() - child.getTop())) return true;
            }
        }
        return false;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void fling() {
        if (content == null || velocityTracker == null) return;
        velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
        int vx = Math.round(velocityTracker.getXVelocity(activePointerId));
        int vy = Math.round(velocityTracker.getYVelocity(activePointerId));
        if (Math.abs(vx) < minFlingVelocity) vx = 0;
        if (Math.abs(vy) < minFlingVelocity) vy = 0;
        if (vx == 0 && vy == 0) return;
        float width = content.getMeasuredWidth() * scale;
        float height = content.getMeasuredHeight() * scale;
        int minX = Math.round(width <= getWidth() ? (getWidth() - width) / 2f : getWidth() - width);
        int minY = Math.round(Math.min(0f, getHeight() - height));
        int maxX = width <= getWidth() ? minX : 0;
        int maxY = height <= getHeight() ? minY : 0;
        scroller.fling(Math.round(translationX), Math.round(translationY), vx, vy, minX, maxX, minY, maxY);
        postInvalidateOnAnimation();
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) velocityTracker.recycle();
        velocityTracker = null;
    }

    @Override protected void onDetachedFromWindow() {
        scroller.forceFinished(true);
        recycleVelocityTracker();
        super.onDetachedFromWindow();
    }

    private void applyTransform() {
        if (content == null) return;
        content.setPivotX(0f);
        content.setPivotY(0f);
        content.setScaleX(scale);
        content.setScaleY(scale);
        content.setTranslationX(translationX);
        content.setTranslationY(translationY);
        if (listener != null) listener.changed(currentPage(), scale);
    }

    private void clampPan() {
        if (content == null || getWidth() == 0 || getHeight() == 0) return;
        float width = content.getMeasuredWidth() * scale;
        float height = content.getMeasuredHeight() * scale;
        translationX = clampAxis(translationX, getWidth(), width);
        translationY = clamp(translationY, Math.min(0f, getHeight() - height), 0f);
    }

    private static float clampAxis(float translation, float viewport, float content) {
        if (content <= viewport) return (viewport - content) / 2f;
        return clamp(translation, viewport - content, 0f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

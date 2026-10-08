package com.rikkahub.wordlite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 命中地图：详情页顶部那一条按正文顺序展开的色带。宽 = 每段字符数 / 全文字符数，四色分别对应
 * 重复 / 改写 / AI 可疑 / 疑似 AI；点一下跳到离落点最近的那一段。
 *
 * 这个 View 只懂画和把点击换成偏移，**所有口径在 {@link HitMapModel} 里**——哪些字归谁、四段为什么互不重叠、
 * 宽度加起来等不等于字符账，那些都在 Host 侧的 HitMapRegression 钉着。这里的任务只有把已经定好的段画准。
 */
public final class HitMapView extends View {

    public interface OnPick {
        void pick(HitMapModel.Band band);
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final int[] colors = new int[4];
    private final float barHeight;
    private final float gapSide;
    private HitMapModel.Map map;
    private OnPick onPick;

    public HitMapView(Context context) {
        super(context);
        barHeight = dip(10);
        gapSide = dip(19);
        track.setStyle(Paint.Style.FILL);
        track.setColor(0xFFD6D6D6);
        fill.setStyle(Paint.Style.FILL);
        setClickable(true);
        setFocusable(true);
        setContentDescription("命中地图");
    }

    /** 深色模式下轨道与四色都要换一遍：同一组颜色压在深底上，红色会变成看不见的暗块。 */
    public void setPalette(int trackColor, int duplicate, int rewritten, int ai, int suspected) {
        track.setColor(trackColor);
        colors[HitMapModel.KIND_DUPLICATE] = duplicate;
        colors[HitMapModel.KIND_REWRITTEN] = rewritten;
        colors[HitMapModel.KIND_AI] = ai;
        colors[HitMapModel.KIND_SUSPECTED] = suspected;
        invalidate();
    }

    public void setMap(HitMapModel.Map map) {
        this.map = map;
        setContentDescription(map == null || map.isEmpty() ? "命中地图：未检出任一段落"
                : "命中地图：" + map.bands.size() + " 段，占全文 " + map.flaggedChars + " 字");
        invalidate();
    }

    public int getBandCount() {
        return map == null ? 0 : map.bands.size();
    }

    public void setOnPick(OnPick onPick) {
        this.onPick = onPick;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, (int) (barHeight + gapSide * 2f + 0.5f));
    }

    @Override protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float top = gapSide;
        box.set(0f, top, width, top + barHeight);
        canvas.drawRoundRect(box, barHeight / 2f, barHeight / 2f, track);
        if (map == null || map.totalChars <= 0) return;
        for (int i = 0; i < map.bands.size(); i++) {
            HitMapModel.Band band = map.bands.get(i);
            float left = width * (float) band.start / (float) map.totalChars;
            float right = width * (float) band.end / (float) map.totalChars;
            // 一段再窄也要看得见：给它一个像素的保底宽度，位置不许因此挪动（左端对齐是真的那个字）。
            if (right - left < 2f) right = Math.min(width, left + 2f);
            fill.setColor(colors[band.kind < 0 || band.kind > 3 ? HitMapModel.KIND_DUPLICATE : band.kind]);
            canvas.drawRect(left, top, right, top + barHeight, fill);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP) return super.onTouchEvent(event);
        HitMapModel.Band band = nearest(event.getX(), getWidth());
        if (band == null) return super.onTouchEvent(event);
        if (onPick != null) onPick.pick(band);
        return true;
    }

    /** 像素换成字符偏移，剩下的口径在 {@link HitMapModel.Map#nearestBand(int)} 里定。 */
    public HitMapModel.Band nearest(float x, int width) {
        if (map == null || map.isEmpty() || width <= 0 || map.totalChars <= 0) return null;
        int offset = (int) (x / (float) width * (float) map.totalChars);
        return map.nearestBand(offset);
    }

    private float dip(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
package com.rikkahub.wordlite;

/** Document coordinates are 96-DPI logical pixels, independent of screen/font scaling. */
public final class PageGeometry {
    public static final float TWIPS_PER_UNIT = 15f;
    public final float width, height, left, right, top, bottom, contentWidth, contentHeight;
    /** Distance from the physical page edge to the header/footer reference line. */
    public final float headerDistance, footerDistance;

    public PageGeometry(DocxDocument.SectionSettings s) {
        float rawWidth = (s.pageWidthTwips > 0 ? s.pageWidthTwips : 11906) / TWIPS_PER_UNIT;
        float rawHeight = (s.pageHeightTwips > 0 ? s.pageHeightTwips : 16838) / TWIPS_PER_UNIT;
        // Word may encode landscape either by swapping pgSz w/h or by keeping
        // portrait dimensions and setting orient="landscape". Normalize both
        // forms before calculating the printable rectangle.
        if (s.landscape && rawWidth < rawHeight) {
            width = rawHeight;
            height = rawWidth;
        } else {
            width = rawWidth;
            height = rawHeight;
        }
        left = margin(s.marginLeftTwips) + Math.max(0, s.gutterTwips) / TWIPS_PER_UNIT;
        right = margin(s.marginRightTwips);
        top = margin(s.marginTopTwips);
        bottom = margin(s.marginBottomTwips);
        headerDistance = (s.headerDistanceTwips >= 0 ? s.headerDistanceTwips : 720) / TWIPS_PER_UNIT;
        footerDistance = (s.footerDistanceTwips >= 0 ? s.footerDistanceTwips : 720) / TWIPS_PER_UNIT;
        contentWidth = width - left - right;
        contentHeight = height - top - bottom;
        if (contentWidth < 1 || contentHeight < 1)
            throw new IllegalArgumentException("页边距超过纸张可排版区域");
    }
    private static float margin(int value) { return (value >= 0 ? value : 1440) / TWIPS_PER_UNIT; }
    public static float twips(int value) { return value / TWIPS_PER_UNIT; }
    public static float points(float value) { return value * 96f / 72f; }
    public float fitScale(float displayWidth) { return displayWidth / width; }
}

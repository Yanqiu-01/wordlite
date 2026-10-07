package com.rikkahub.wordlite;

/**
 * Word's raise/lower geometry for superscript and subscript runs.
 *
 * Word scales a vertAlign run to the font's OS/2 ySuper/ySub sizes and moves
 * the baseline by ySuper/ySubYOffset. Both the advance used for line breaking
 * and the line box follow from that one transform, so the arithmetic lives here
 * instead of inside the spans and can be asserted without a device.
 */
public final class ScriptGeometry {
    public final boolean superscript;
    /** Script em divided by base em. */
    public final float scale;
    /** Baseline shift in base-em units; positive raises the glyphs. */
    public final float shift;
    /** Space the script needs above the base baseline, in base-em units. */
    public final float ascentEm;
    /** Space the script needs below the base baseline, in base-em units. */
    public final float descentEm;

    private ScriptGeometry(boolean superscript, float scale, float shift,
                           float ascentEm, float descentEm) {
        this.superscript = superscript;
        this.scale = scale; this.shift = shift;
        this.ascentEm = ascentEm; this.descentEm = descentEm;
    }

    public static ScriptGeometry of(boolean superscript, FontScriptMetrics metrics) {
        FontScriptMetrics m = metrics == null ? FontScriptMetrics.DEFAULT : metrics;
        // FontScriptMetrics.offset() follows the canvas direction (down is
        // positive); Word raises superscripts, so flip it once here.
        return raised(superscript, m, -m.offset(superscript), m.scale(superscript));
    }

    /** Same line-box rule for text raised by w:position, which keeps its size. */
    public static ScriptGeometry raised(float shiftEm, FontScriptMetrics metrics) {
        FontScriptMetrics m = metrics == null ? FontScriptMetrics.DEFAULT : metrics;
        return raised(true, m, shiftEm, 1f);
    }

    private static ScriptGeometry raised(boolean superscript, FontScriptMetrics m,
                                         float shift, float scale) {
        float ascentRatio = m.ascentFraction * m.lineHeightRatio;
        float descentRatio = Math.max(0f, m.lineHeightRatio - ascentRatio);
        float glyphAscent = scale * ascentRatio, glyphDescent = scale * descentRatio;
        // The demand is the transformed glyph box measured from the base
        // baseline: raising by shift costs ascent, dropping by shift costs
        // descent, and the side the offset closes shrinks by the same amount.
        // Charging a subscript its full unscaled ascent would grow rows that
        // Word leaves alone, which is the pagination drift this class exists to
        // remove.
        return new ScriptGeometry(superscript, scale, shift,
                Math.max(0f, shift + glyphAscent), Math.max(0f, glyphDescent - shift));
    }

    public float ascentPx(float baseSizePx) { return baseSizePx * ascentEm; }

    public float descentPx(float baseSizePx) { return baseSizePx * descentEm; }

    /** Type size in points once the script scale is applied. */
    public float effectiveSizePt(float basePt) { return basePt * scale; }

    /**
     * Word's line box for one line: the paragraph box grown only as far as the
     * raised or lowered text on this line needs. Exact line spacing clips instead
     * of growing, and a document grid snaps the grown row to whole grid rows.
     */
    public static int[] lineBox(int baseAscent, int baseDescent, int needAscent, int needDescent,
                                int gridPitchPx, boolean clip) {
        if (clip || (needAscent <= baseAscent && needDescent <= baseDescent))
            return new int[]{baseAscent, baseDescent};
        // Raised text pushes the line upward: the baseline keeps its distance
        // from the bottom, so the growth lands above it and every following line
        // shifts down exactly like Word.
        int ascent = Math.max(baseAscent, needAscent);
        int descent = Math.max(baseDescent, needDescent);
        if (gridPitchPx > 0) {
            // On a document grid the grown row snaps to whole grid rows, and the
            // leftover space goes below the baseline for the same reason.
            int grown = ascent + descent;
            int rows = Math.max(1, (int) Math.ceil(grown / (double) gridPitchPx));
            descent = Math.max(descent, rows * gridPitchPx - ascent);
        }
        return new int[]{ascent, descent};
    }
}

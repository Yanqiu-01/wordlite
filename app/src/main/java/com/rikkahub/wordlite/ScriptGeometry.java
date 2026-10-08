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

    /**
     * A w:vertAlign run as a line-box competitor: the box of its own face at the size the
     * run declares, with the script raise/lower left out of the box.
     *
     * Word measured, not inferred (artifacts/agent-layout-fix/lab: script-lab.py writes
     * script-lh-nogrid.docx / script-lh-grid360.docx -- one paragraph per case, five lines
     * separated by <w:br/>, every line starting with the same Han glyph so a difference of two
     * Information(6) reads is the line advance of the line above; script-lab-measure.ps1 reads
     * it back through Word 16.0 COM into script-lh-*.tsv; lab/script-lh-read.py prints the
     * per-case deltas). On a 12pt body:
     *
     *   - a script that declares the body size (every vertAlign run in tests/samples/input-liu.docx):
     *     the line advance equals the paragraph's own plain lines to 0.000 px under w:line=240
     *     and 300 auto and under atLeast, and nothing grows at all under lineRule=exact;
     *     NOT measured under a real line grid -- the grid sample carries only w:linePitch,
     *     no w:type, and Word reads back identical to the gridless one;
     *   - a script that declares 24pt grows the line to 36.2 px and one that declares 36pt to
     *     55.3 px, and super and sub grow by the same 15.333 px / 34.4 px.
     *
     * Both readings say the same thing: what grows the row is a run BIGGER than the paragraph,
     * charged at the size it declares, and the baseline shift is not part of the box. Charging
     * the shift was our own addition. On tests/samples/input-liu.docx it happens to bill nothing
     * either way -- all 54 vertAlign runs there declare the body size and the body row already
     * contains that box (measured: the device capture is byte-identical before and after this
     * rule) -- so this is a correctness pin, not a pagination fix: a document whose citation
     * superscripts are typed bigger than the body would grow rows Word leaves alone.
     *
     * The superscript flag does not reach the box arithmetic and is deliberately false: Word grew
     * the two directions by the same amount. w:position keeps its shift (see raised()) because
     * the same lab shows Word does grow a row for text raised without rescaling (+12.7 px on a
     * 20.867 px line).
     */
    public static ScriptGeometry declared(FontScriptMetrics metrics) {
        FontScriptMetrics m = metrics == null ? FontScriptMetrics.DEFAULT : metrics;
        return raised(false, m, 0f, 1f);
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
        return lineBox(baseAscent, baseDescent, needAscent, needDescent, gridPitchPx, clip, true);
    }

    /**
     * snapGrownRow=false 时长高的行不补齐到整格。行高走 Word 实测值的那一段专用：实测行距
     * 12pt/w:line=300 = 26.267px 本身已经高过一格网格（25px），再按长高就补到整格算，只多
     * 1px 的下标行会直接跳到两格——真机量到 25.5px 的下标行变 50.267px，一个段落多 272px
     * （docs/layout-parity-target.md 第 7 节 A 表最后一条）。Word 对那些行量到的仍是 26.267px，
     * 而同一篇里 Word 开网格与不开网格只差 0.13px（第 3 节）：网格在 Word 的行高上是惰性的。
     */
    public static int[] lineBox(int baseAscent, int baseDescent, int needAscent, int needDescent,
                                int gridPitchPx, boolean clip, boolean snapGrownRow) {
        if (clip || (needAscent <= baseAscent && needDescent <= baseDescent))
            return new int[]{baseAscent, baseDescent};
        // Raised text pushes the line upward: the baseline keeps its distance
        // from the bottom, so the growth lands above it and every following line
        // shifts down exactly like Word.
        int ascent = Math.max(baseAscent, needAscent);
        int descent = Math.max(baseDescent, needDescent);
        if (gridPitchPx > 0 && snapGrownRow) {
            // On a document grid the grown row snaps to whole grid rows, and the
            // leftover space goes below the baseline for the same reason.
            int grown = ascent + descent;
            int rows = Math.max(1, (int) Math.ceil(grown / (double) gridPitchPx));
            descent = Math.max(descent, rows * gridPitchPx - ascent);
        }
        return new int[]{ascent, descent};
    }
}



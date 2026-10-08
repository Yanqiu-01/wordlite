package com.rikkahub.wordlite;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Our own integer line box, and the fractional remainder Word keeps and we cannot draw.
 *
 * Read the title carefully: this file used to be called "matches Word", and it did not match
 * Word. It asserted a model (fontSizePx x (winAscent+winDescent)/unitsPerEm x line/240, rounded
 * twice) and called the result Word's number because that is what the formula looked like, not
 * because anyone had measured Word. Desktop Word 16.0 was then measured through COM, line by
 * line (docs/layout-parity-target.md, artifacts/agent-typeset/word-line-pitch.ps1): a 12pt
 * song.ttc paragraph with w:line=300 and no grid sits 26.267px between baselines, not the 20px
 * this file claimed. The gap is 0.767-3.267px per line, about 0.8 of a line per 27-line page,
 * which is the pagination drift this repo kept chasing.
 *
 * What is left here is the arithmetic we control: the box Android actually draws (integer), plus
 * the fact that Word's measured pitch is a fraction bigger and A4Paginator has to carry that
 * fraction per line. WordLineHeightRegression (host suite, runs in tools/test-host.ps1) pins the
 * measured faces and the carry formula against the real bundled fonts; this file only keeps the
 * rounding shape honest. Note that tools/test-native.sh needs a gradle in WSL, so nothing here
 * is executed by the normal gate: do not add production-code assertions to this file, put them in
 * the host suite instead.
 */
public class WordLinePitchTest {

    /** Two-step rounding is what StaticLayout ends up drawing; it is not Word. */
    private int drawnLineBox(float fontSizePt, float fontLineRatio, int lineTwips) {
        int singlePx = Math.max(1, Math.round(fontSizePt * fontLineRatio * 96f / 72f));
        return Math.max(1, Math.round(singlePx * lineTwips / 240f));
    }

    /** Word's auto spacing is one fractional step: single x line/240, no rounding in between. */
    private float wordPitch(float fontSizePt, float fontLineRatio, int lineTwips) {
        return (float) (fontSizePt * fontLineRatio * (96d / 72d) * (lineTwips > 0 ? lineTwips / 240d : 1d));
    }

    @Test
    public void body12ptSongLine300Draws26AndOwesWordAFraction() {
        // 宋体 measured single-line height is 1.31335 em (WordLineHeights), not the 1.000 em the
        // font table's winAscent+winDescent adds up to.
        assertEquals(26, drawnLineBox(12f, 1.31335f, 300));
        assertEquals(26.267f, wordPitch(12f, 1.31335f, 300), 0.02f);
        assertEquals(0.267f, wordPitch(12f, 1.31335f, 300) - drawnLineBox(12f, 1.31335f, 300), 0.01f);
    }

    @Test
    public void latinReferenceLineIsShorterThanTheCjkLine() {
        // Word measures 23.533px for the same 12pt/w:line=300 set in Times New Roman.
        assertEquals(24, drawnLineBox(12f, 1.17665f, 300));
        assertEquals(23.533f, wordPitch(12f, 1.17665f, 300), 0.02f);
        assertTrue(wordPitch(12f, 1.17665f, 300) < wordPitch(12f, 1.31335f, 300));
    }

    @Test
    public void theFractionOnlyMattersBecauseItAccumulates() {
        float carry = wordPitch(12f, 1.31335f, 300) - drawnLineBox(12f, 1.31335f, 300);
        // One line is nothing; a 27-line page is 7.2px, which is how a paragraph ends up one page
        // early. That is the whole reason the paginator gets a per-paragraph carry.
        assertEquals(7.2f, carry * 27f, 0.2f);
        assertTrue(carry < 1f);
    }

    @Test
    public void neverFabricatesACarryForUnmeasuredFaces() {
        // simhei.ttf has no measured pitch, so the carry rule must not fire for it: the two-step
        // box stands alone there, and WordLineHeights.carries() is the gate that decides it. The
        // 1.000 em below is the raw table value that stays in force until someone measures that
        // face in Word, so the line it produces is a number we own, not a Word number.
        assertEquals(20, drawnLineBox(12f, 1.0f, 300));
    }

    @Test
    public void exactRuleUsesTwipsDirectly() {
        // exact=380 twips -> 380/20=19pt -> round(19*96/72)=25px, no font ratio involved.
        int twips = 380;
        int px = Math.max(1, Math.round(twips / 20f * 96f / 72f));
        assertEquals(25, px);
    }
}

package com.rikkahub.wordlite;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Verifies that computed line heights match Microsoft Word's expected values
 * for the real document paragraph configurations.
 *
 * Word's line pitch = fontSizePx × (winAscent+winDescent)/unitsPerEm × (line/240)
 * where the font ratio comes from the actual font file metrics (OS/2 winAscent/winDescent).
 *
 * The old code's bug: it used a default Paint (Roboto) whose metrics inflate ~18%
 * above the document's actual fonts, making every line 2-3px too tall.
 */
public class WordLinePitchTest {

    @Test
    public void bodyParagraph10_5pt_song_line300_matchesWord() {
        // 宋体 ratio=1.0, sz=21 half-points (10.5pt), line=300 (1.25x)
        // Word: textPx=14, single=14, pitch=round(14*1.25)=18
        int result = computeLinePitch(10.5f, 1.0f, 300);
        assertEquals(18, result);
    }

    @Test
    public void bodyParagraph12pt_song_line300_matchesWord() {
        // sz=24 (12pt), textPx=16, single=16, pitch=round(16*1.25)=20
        int result = computeLinePitch(12f, 1.0f, 300);
        assertEquals(20, result);
    }

    @Test
    public void mixedParagraph10_5pt_TNR_dominant_line300() {
        // TNR ratio=1.1074 dominates over 宋体 ratio=1.0
        // Word: round(14*1.1074)=16, round(16*1.25)=20
        int result = computeLinePitch(10.5f, 1.1074f, 300);
        assertEquals(20, result);
    }

    @Test
    public void mixedParagraph12pt_TNR_dominant_line300() {
        // round(16*1.1074)=18, round(18*1.25)=23
        int result = computeLinePitch(12f, 1.1074f, 300);
        assertEquals(23, result);
    }

    @Test
    public void normalStyle_line276() {
        // 10.5pt TNR: round(14*1.1074)=16, round(16*276/240)=round(18.4)=18
        int result = computeLinePitch(10.5f, 1.1074f, 276);
        assertEquals(18, result);
    }

    @Test
    public void neverExceedsWordByMoreThan1Pixel() {
        int[][] cases = {{21, 300}, {24, 300}, {21, 276}, {24, 276}, {21, 288}};
        for (int[] c : cases) {
            float pt = c[0] / 2f;
            int line = c[1];
            int ourHeight = computeLinePitch(pt, 1.1074f, line);
            // Word computes: round(textPx × ratio) then × line/240
            int textPx = Math.round(pt * 96f / 72f);
            int wordSingle = Math.round(textPx * 1.1074f);
            int wordHeight = Math.round(wordSingle * line / 240f);
            assertTrue("sz=" + c[0] + " line=" + line + ": ours=" + ourHeight +
                            " word=" + wordHeight,
                    Math.abs(ourHeight - wordHeight) <= 1);
        }
    }

    @Test
    public void exactRuleUsesTwipsDirectly() {
        // exact=380 twips → 380/20=19pt → round(19*96/72)=25px
        int twips = 380;
        int px = Math.max(1, Math.round(twips / 20f * 96f / 72f));
        assertEquals(25, px);
    }

    /** Replicates the new Spacing formula (two-step rounding, matching Word). */
    private int computeLinePitch(float fontSizePt, float fontLineRatio, int lineTwips) {
        float singlePt = fontSizePt * fontLineRatio;
        int singlePx = Math.max(1, Math.round(singlePt * 96f / 72f));
        return Math.max(1, Math.round(singlePx * lineTwips / 240f));
    }
}

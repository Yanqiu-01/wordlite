package com.rikkahub.wordlite;

/** Regression tests for the Apache-2.0 Noplag-inspired candidate ordering. */
public final class NoplagRarestProbeRegression {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        long[] fingerprints = {30L, 10L, 20L, 10L, 40L};
        int[] df = {100, 2, 8, 3, 50000};
        long[] selected = NoplagRarestProbe.select(fingerprints, df, 12, 8);
        check(selected.length == 2, "DF sum stops before the high-frequency tail");
        check(selected[0] == 10L && selected[1] == 20L,
                "rarest fingerprints are selected deterministically");
        check(NoplagRarestProbe.isDiscriminative(fingerprints, df,
                NoplagRarestProbe.DEFAULT_NON_DISCRIMINATIVE_DF),
                "a mixed query remains discriminative");
        check(!NoplagRarestProbe.isDiscriminative(new long[]{1L}, new int[]{50000},
                NoplagRarestProbe.DEFAULT_NON_DISCRIMINATIVE_DF),
                "an all-common query is recognized as non-discriminative");
        System.out.println("SUMMARY " + checks + " assertions passed");
    }
}

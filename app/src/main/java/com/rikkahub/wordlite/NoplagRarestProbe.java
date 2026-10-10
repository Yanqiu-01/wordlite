package com.rikkahub.wordlite;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;

/**
 * Pure-Java candidate selection adapted from Noplag's DF-ordered probe idea.
 * This class only chooses fingerprints to inspect; it never decides a match.
 *
 * The thresholds are the upstream retrieval defaults, not application size
 * limits. Final scoring and hit spans remain in TextCorpus.
 */
final class NoplagRarestProbe {
    static final int DEFAULT_DF_SUM_LIMIT = 15000;
    static final int DEFAULT_MAX_PROBES = 96;
    static final int DEFAULT_NON_DISCRIMINATIVE_DF = 50000;

    private NoplagRarestProbe() { }

    static boolean isDiscriminative(long[] fingerprints, int[] documentFrequency, int threshold) {
        if (fingerprints == null || documentFrequency == null
                || fingerprints.length == 0 || fingerprints.length != documentFrequency.length)
            return false;
        for (int i = 0; i < documentFrequency.length; i++)
            if (documentFrequency[i] < threshold) return true;
        return threshold <= 0;
    }

    /**
     * Return unique fingerprints ordered by ascending document frequency,
     * then by fingerprint value for deterministic results.
     */
    static long[] select(long[] fingerprints, int[] documentFrequency,
                         int dfSumLimit, int maxProbes) {
        if (fingerprints == null || documentFrequency == null
                || fingerprints.length == 0 || fingerprints.length != documentFrequency.length
                || maxProbes <= 0 || dfSumLimit < 0)
            return new long[0];
        Integer[] order = new Integer[fingerprints.length];
        for (int i = 0; i < order.length; i++) order[i] = Integer.valueOf(i);
        Arrays.sort(order, new Comparator<Integer>() {
            public int compare(Integer left, Integer right) {
                int a = documentFrequency[left.intValue()];
                int b = documentFrequency[right.intValue()];
                if (a != b) return a < b ? -1 : 1;
                return Long.compare(fingerprints[left.intValue()], fingerprints[right.intValue()]);
            }
        });
        HashSet<Long> seen = new HashSet<Long>();
        long[] out = new long[Math.min(maxProbes, fingerprints.length)];
        int count = 0;
        long total = 0L;
        for (int i = 0; i < order.length && count < maxProbes; i++) {
            int at = order[i].intValue();
            Long fingerprint = Long.valueOf(fingerprints[at]);
            if (!seen.add(fingerprint)) continue;
            int df = Math.max(0, documentFrequency[at]);
            if (count > 0 && total + df > dfSumLimit) break;
            out[count++] = fingerprint.longValue();
            total += df;
        }
        return Arrays.copyOf(out, count);
    }
}

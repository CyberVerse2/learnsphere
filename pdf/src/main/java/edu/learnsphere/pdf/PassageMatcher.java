package edu.learnsphere.pdf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds where a passage occurs in a page's text, tolerating small differences. Both strings are expected in
 * {@linkplain TextNormalizer#matchForm match form} (letters and digits only, lower case), so whitespace,
 * punctuation, case, accents and line-end hyphens never get in the way.
 *
 * <p>An exact occurrence wins. Otherwise the passage and the page are compared through shared character
 * q-grams: runs of consecutive shared q-grams on the same diagonal are chained in order, paying for gaps of
 * different lengths on the two sides, and the best chain gives the matched region. This tolerates typos and
 * OCR slips (a broken run), words added or missing on either side (a gap), and passages that continue on
 * the next or previous page (only a prefix or suffix of the passage is on this page).
 */
final class PassageMatcher {

    /** Passages shorter than this must occur exactly. */
    static final int MIN_FUZZY_LENGTH = 12;
    /** Q-grams occurring more often than this on the page are too common to anchor a match. */
    private static final int MAX_OCCURRENCES = 4;
    /** Longest gap (in characters, either side) bridged between two runs of a chain. */
    private static final int MAX_GAP = 400;
    /** Runs kept for chaining; more only happens with highly repetitive text. */
    private static final int MAX_RUNS = 2_000;

    /**
     * A matched region of the page, {@code [start, end)}, and how many passage characters it accounts for.
     * {@code segments} holds the page ranges that actually match, as {@code [from0, to0, from1, to1, ...]} in
     * page order; text between them (a footer between two columns, a changed word) belongs to the region but
     * not to the passage.
     */
    record Match(int start, int end, int covered, int[] segments) {

        /** Number of matching page ranges. */
        int segmentCount() {
            return segments.length / 2;
        }

        int segmentStart(int index) {
            return segments[2 * index];
        }

        int segmentEnd(int index) {
            return segments[2 * index + 1];
        }
    }

    private PassageMatcher() {
    }

    /** The region of {@code page} matching {@code passage}, or null when there is no convincing match. */
    static Match find(String page, String passage) {
        if (page.isEmpty() || passage.isEmpty()) {
            return null;
        }
        int exact = page.indexOf(passage);
        if (exact >= 0) {
            return new Match(exact, exact + passage.length(), passage.length(), new int[] {exact, exact + passage.length()});
        }
        if (passage.length() < MIN_FUZZY_LENGTH) {
            return null;
        }
        int q = passage.length() >= 48 ? 8 : 5;
        if (page.length() < q) {
            return null;
        }
        List<Run> runs = runs(page, passage, q);
        if (runs.isEmpty()) {
            return null;
        }
        return bestChain(runs, page, passage, q);
    }

    private static List<Run> runs(String page, String passage, int q) {
        Map<Long, int[]> index = new HashMap<>();
        for (int j = 0; j + q <= page.length(); j++) {
            index.merge(hash(page, j, q), new int[] {j}, PassageMatcher::append);
        }
        Map<Integer, Run> open = new HashMap<>();
        List<Run> runs = new ArrayList<>();
        for (int i = 0; i + q <= passage.length(); i++) {
            int[] positions = index.get(hash(passage, i, q));
            if (positions == null || positions.length > MAX_OCCURRENCES) {
                continue;
            }
            for (int j : positions) {
                if (!page.regionMatches(j, passage, i, q)) {
                    continue;
                }
                int diagonal = j - i;
                Run run = open.get(diagonal);
                if (run != null && run.lastI == i - 1) {
                    run.lastI = i;
                } else {
                    run = new Run(i, j);
                    open.put(diagonal, run);
                    runs.add(run);
                }
            }
        }
        if (runs.size() > MAX_RUNS) {
            runs.sort(Comparator.comparingInt((Run r) -> r.lastI - r.iFrom).reversed());
            runs = new ArrayList<>(runs.subList(0, MAX_RUNS));
        }
        runs.sort(Comparator.comparingInt((Run r) -> r.iFrom).thenComparingInt(r -> r.jFrom));
        return runs;
    }

    private static Match bestChain(List<Run> runs, String page, String passage, int q) {
        int passageLength = passage.length();
        int n = runs.size();
        double[] score = new double[n];
        int[] previous = new int[n];
        int best = 0;
        for (int k = 0; k < n; k++) {
            Run current = runs.get(k);
            int length = current.length(q);
            score[k] = length;
            previous[k] = -1;
            for (int r = 0; r < k; r++) {
                Run before = runs.get(r);
                int gapPassage = current.iFrom - before.iTo(q);
                int gapPage = current.jFrom - before.jTo(q);
                if (gapPassage < 0 || gapPage < 0 || Math.max(gapPassage, gapPage) > MAX_GAP) {
                    continue;
                }
                double penalty = 0.5 * Math.abs(gapPassage - gapPage) + 0.05 * Math.min(gapPassage, gapPage);
                double candidate = score[r] + length - penalty;
                if (candidate > score[k]) {
                    score[k] = candidate;
                    previous[k] = r;
                }
            }
            if (score[k] > score[best]) {
                best = k;
            }
        }
        int covered = 0;
        Run first = null;
        Run last = runs.get(best);
        List<Run> chain = new ArrayList<>();
        for (int k = best; k >= 0; k = previous[k]) {
            first = runs.get(k);
            chain.add(first);
            covered += first.length(q);
        }
        // Q-grams too common to anchor on may hide a few matching characters at either end; take them back.
        int iStart = first.iFrom;
        int start = first.jFrom;
        while (iStart > 0 && start > 0 && passage.charAt(iStart - 1) == page.charAt(start - 1)) {
            iStart--;
            start--;
            covered++;
        }
        int iEnd = last.iTo(q);
        int end = last.jTo(q);
        while (iEnd < passageLength && end < page.length() && passage.charAt(iEnd) == page.charAt(end)) {
            iEnd++;
            end++;
            covered++;
        }
        boolean touchesStart = iStart <= q;
        boolean touchesEnd = iEnd >= passageLength - q;
        boolean enough = covered * 10 >= passageLength * 3 || ((touchesStart || touchesEnd) && covered >= 20);
        boolean dense = covered * 2 >= end - start;
        if (covered < Math.min(passageLength, 16) || !enough || !dense) {
            return null;
        }
        int[] segments = new int[2 * chain.size()];
        for (int k = 0; k < chain.size(); k++) {
            Run run = chain.get(chain.size() - 1 - k);
            segments[2 * k] = run.jFrom;
            segments[2 * k + 1] = run.jTo(q);
        }
        segments[0] = start;
        segments[segments.length - 1] = end;
        return new Match(start, end, covered, segments);
    }

    private static long hash(String text, int from, int length) {
        long h = 1125899906842597L;
        for (int i = from; i < from + length; i++) {
            h = 31 * h + text.charAt(i);
        }
        return h;
    }

    private static int[] append(int[] existing, int[] added) {
        if (existing.length > MAX_OCCURRENCES) {
            return existing; // already too common; stop growing
        }
        int[] merged = new int[existing.length + added.length];
        System.arraycopy(existing, 0, merged, 0, existing.length);
        System.arraycopy(added, 0, merged, existing.length, added.length);
        return merged;
    }

    /** Consecutive shared q-grams on one diagonal: passage positions iFrom..lastI match page jFrom.. . */
    private static final class Run {
        final int iFrom;
        final int jFrom;
        int lastI;

        Run(int i, int j) {
            this.iFrom = i;
            this.jFrom = j;
            this.lastI = i;
        }

        int length(int q) {
            return lastI - iFrom + q;
        }

        int iTo(int q) {
            return lastI + q;
        }

        int jTo(int q) {
            return jFrom + (lastI - iFrom) + q;
        }
    }
}

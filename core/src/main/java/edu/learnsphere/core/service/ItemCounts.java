package edu.learnsphere.core.service;

/** Counts for the Studio's item tabs and badges. */
public record ItemCounts(int draft, int approved, int rejected, int retired, int unsupported, int flagged) {

    public int total() {
        return draft + approved + rejected + retired;
    }
}

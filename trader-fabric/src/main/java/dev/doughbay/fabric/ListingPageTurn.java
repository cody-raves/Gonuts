package dev.doughbay.fabric;

/** A sent Next click is not an observed new page. Never count the old page twice. */
final class ListingPageTurn {
    enum Result { NONE, WAITING, ARRIVED, EXPIRED }
    private String previousPage;
    private long requestedAt;

    void reset() { previousPage = null; requestedAt = 0; }
    boolean pending() { return previousPage != null; }

    void requested(String page, long now) {
        previousPage = page;
        requestedAt = now;
    }

    Result observe(String page, long now, boolean rowsLoaded) {
        if (previousPage == null) return Result.NONE;
        if (rowsLoaded && !previousPage.equals(page)) {
            reset();
            return Result.ARRIVED;
        }
        return now - requestedAt >= 8_000 ? Result.EXPIRED : Result.WAITING;
    }
}

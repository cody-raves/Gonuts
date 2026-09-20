package dev.doughbay.fabric;

import dev.doughbay.core.model.Listing;
import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.Opportunity;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Chooses the listing a manual test buy will go after.
 *
 * <p>A test buy exists to prove the click path on something cheap, so the
 * pick is the least expensive scanned listing of the requested item at or
 * under the operator's ceiling. Only listings with a seller name qualify,
 * because live execution refuses to guess between identical rows.
 */
public final class TestBuyPicker {
    private TestBuyPicker() {
    }

    /** The stack size in "dirt x64" or "dirt*64"; 0 when none is given. */
    static int requiredCount(String typed) {
        if (typed == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)\\s*[x*]\\s*(\\d{1,3})\\s*$").matcher(typed.strip());
        if (!m.find()) return 0;
        int count = Integer.parseInt(m.group(1));
        return count >= 1 && count <= 64 ? count : 0;
    }

    /** Accepts "dirt", "minecraft:dirt", or "dirt x64"; returns "" when the text is unusable. */
    static String normalizeItemId(String typed) {
        if (typed == null) return "";
        String text = typed.strip().toLowerCase(Locale.ROOT)
                .replaceAll("(?i)\\s*[x*]\\s*\\d{1,3}\\s*$", "");
        if (text.isEmpty()) return "";
        if (!text.contains(":")) text = "minecraft:" + text;
        return text.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? text : "";
    }

    static Optional<Listing> cheapestAtOrUnder(List<Listing> listings, String itemId, long maxTotalPrice) {
        if (listings == null || itemId == null || itemId.isBlank() || maxTotalPrice <= 0) {
            return Optional.empty();
        }
        return listings.stream()
                .filter(l -> l != null && l.isValid()
                        && itemId.equals(l.itemId())
                        && l.totalPrice() <= maxTotalPrice
                        && l.sellerName() != null && !l.sellerName().isBlank()
                        && l.listingKey() != null && !l.listingKey().isBlank())
                .min(Comparator.comparingLong(Listing::totalPrice)
                        .thenComparingInt(Listing::itemCount)
                        .thenComparing(Listing::listingKey));
    }

    /**
     * An opportunity shaped for the execution driver. Valuation fields are
     * deliberately zero. Statistics come from the market feed when it has
     * this item, otherwise a placeholder priced at the listing itself, since
     * every screen that shows an opportunity reads its statistics.
     */
    static Opportunity asManualOpportunity(Listing listing, List<MarketStats> markets, long now) {
        MarketStats stats = (markets == null ? List.<MarketStats>of() : markets).stream()
                .filter(m -> m != null && listing.itemKey().equals(m.itemKey())
                        && listing.bucket() == m.bucket())
                .findFirst()
                .orElseGet(() -> placeholderStats(listing, now));
        return new Opportunity(listing, stats, listing.totalPrice(), listing.totalPrice(),
                0, 0, 0, 0, 0, 0, List.of("Manual test buy"));
    }

    public static MarketStats placeholderStats(Listing listing, long now) {
        double price = listing.totalPrice();
        return new MarketStats(listing.itemKey(), listing.bucket(), 0, 0, 0, 0, 0,
                price, price, price, price, price, 0, 0, 0, 0, now);
    }
}

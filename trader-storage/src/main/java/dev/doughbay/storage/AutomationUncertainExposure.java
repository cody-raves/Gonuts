package dev.doughbay.storage;

/**
 * Exact intent retained while a real BUY/LIST may have changed server state
 * but has not yet produced enough evidence to create or advance a Position.
 */
public record AutomationUncertainExposure(
        boolean active,
        String phase,
        String listingKey,
        String itemKey,
        String itemId,
        int itemCount,
        long buyPrice,
        long targetPrice,
        String seller,
        long observedAt
) {
    public AutomationUncertainExposure {
        phase = safe(phase);
        listingKey = safe(listingKey);
        itemKey = safe(itemKey);
        itemId = safe(itemId);
        seller = safe(seller);
        if (itemCount < 0 || buyPrice < 0 || targetPrice < 0 || observedAt < 0) {
            throw new IllegalArgumentException("uncertain exposure values cannot be negative");
        }
        if (active && (phase.isBlank() || itemKey.isBlank() || itemId.isBlank()
                || itemCount <= 0)) {
            throw new IllegalArgumentException(
                    "active uncertain exposure requires exact item, count and phase");
        }
        if (!active) {
            phase = "";
            listingKey = "";
            itemKey = "";
            itemId = "";
            itemCount = 0;
            buyPrice = 0;
            targetPrice = 0;
            seller = "";
            observedAt = 0;
        }
    }

    public static AutomationUncertainExposure none() {
        return new AutomationUncertainExposure(false, "", "", "", "",
                0, 0, 0, "", 0);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

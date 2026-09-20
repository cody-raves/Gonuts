package dev.doughbay.core.model;

/**
 * An active auction listing observed at {@code observedAt} (epoch millis).
 * {@code timeLeftMillis} is null when the API does not report it.
 */
public record Listing(
        String listingKey,
        long observedAt,
        String sellerUuid,
        String sellerName,
        String itemKey,
        String itemId,
        int itemCount,
        long totalPrice,
        Long timeLeftMillis
) {
    public double unitPrice() {
        return (double) totalPrice / itemCount;
    }

    public StackBucket bucket() {
        return StackBucket.of(itemCount);
    }

    public boolean isValid() {
        return totalPrice > 0
                && itemCount > 0
                && itemKey != null && !itemKey.isBlank();
    }
}

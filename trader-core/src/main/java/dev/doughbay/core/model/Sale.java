package dev.doughbay.core.model;

/**
 * A completed auction transaction, normalized. Prices are in whole in-game
 * currency units; {@code soldAt} is epoch milliseconds.
 */
public record Sale(
        String transactionHash,
        long soldAt,
        String sellerUuid,
        String sellerName,
        String itemKey,
        String itemId,
        int itemCount,
        long totalPrice
) {
    public double unitPrice() {
        return (double) totalPrice / itemCount;
    }

    public StackBucket bucket() {
        return StackBucket.of(itemCount);
    }

    /**
     * Deterministic dedup hash so the same transaction observed on two
     * overlapping pages is stored once.
     */
    public static String computeHash(long soldAt, String sellerUuid, String fingerprintCanonical,
                                     int count, long totalPrice) {
        return ItemFingerprint.sha256(
                soldAt + "|" + sellerUuid + "|" + fingerprintCanonical + "|" + count + "|" + totalPrice);
    }

    /** Basic sanity: rejects the malformed records the plan calls out. */
    public boolean isValid() {
        return totalPrice > 0
                && itemCount > 0
                && soldAt > 0
                && itemKey != null && !itemKey.isBlank()
                && sellerUuid != null && !sellerUuid.isBlank();
    }
}

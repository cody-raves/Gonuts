package dev.doughbay.core.model;

/**
 * One buy-then-resell position. {@code mode} is "PAPER" or "REAL".
 * Nullable longs are 0 when unset; {@code realizedProfit} is NaN until closed.
 */
public record Position(
        long positionId,
        String mode,
        String itemKey,
        StackBucket bucket,
        int quantity,
        long purchasePrice,
        long targetPrice,
        long purchasedAt,
        long listedAt,
        long closedAt,
        long salePrice,
        double realizedProfit,
        PositionStatus status
) {
    public Position withStatus(PositionStatus newStatus) {
        return new Position(positionId, mode, itemKey, bucket, quantity, purchasePrice,
                targetPrice, purchasedAt, listedAt, closedAt, salePrice, realizedProfit, newStatus);
    }

    public Position listed(long at) {
        return new Position(positionId, mode, itemKey, bucket, quantity, purchasePrice,
                targetPrice, purchasedAt, at, closedAt, salePrice, realizedProfit,
                PositionStatus.SIMULATED_LISTED);
    }

    public Position closed(PositionStatus finalStatus, long at, long actualSalePrice, double profit) {
        return new Position(positionId, mode, itemKey, bucket, quantity, purchasePrice,
                targetPrice, purchasedAt, listedAt, at, actualSalePrice, profit, finalStatus);
    }
}

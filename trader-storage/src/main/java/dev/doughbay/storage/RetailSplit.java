package dev.doughbay.storage;

import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;
import dev.doughbay.core.model.StackBucket;

/** Exact cost allocation: integer rounding stays with the remaining stock. */
public record RetailSplit(Position single, Position remainder) {
    public static RetailSplit of(Position parent, long singleId, long remainderId, long unitTarget) {
        return of(parent, singleId, remainderId, unitTarget, 1);
    }

    /**
     * Takes {@code count} items off the lot as one position: a single, or a
     * whole stack to be sold as a stack once the single slots are full. Cost
     * is allocated per item and the rounding stays with the remainder.
     */
    public static RetailSplit of(Position parent, long singleId, long remainderId, long unitTarget, int count) {
        if (parent.status() != PositionStatus.PURCHASED || parent.quantity() < 2
                || count < 1 || count >= parent.quantity()
                || parent.purchasePrice() < 0 || unitTarget <= 0 || singleId <= 0 || remainderId <= 0
                || singleId == remainderId || singleId == parent.positionId() || remainderId == parent.positionId())
            throw new IllegalArgumentException("Invalid retail split");
        long unitCost = parent.purchasePrice() / parent.quantity();
        long taken = Math.multiplyExact(unitCost, (long) count);
        return new RetailSplit(child(parent, singleId, count, taken, Math.multiplyExact(unitTarget, (long) count)),
                child(parent, remainderId, parent.quantity() - count, parent.purchasePrice() - taken,
                        Math.multiplyExact(unitTarget, (long) (parent.quantity() - count))));
    }

    private static Position child(Position parent, long id, int count, long cost, long target) {
        return new Position(id, parent.mode(), parent.itemKey(), StackBucket.of(count), count,
                cost, target, parent.purchasedAt(), 0, 0, 0, Double.NaN, PositionStatus.PURCHASED);
    }
}

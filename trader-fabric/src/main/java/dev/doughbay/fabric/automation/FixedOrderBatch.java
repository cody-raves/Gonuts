package dev.doughbay.fabric.automation;

/** Explicit whole-batch sizing. A constraint means wait, never substitute a smaller purchase. */
final class FixedOrderBatch {
    enum Gate { READY, INVALID, NOT_WHOLE_STACKS, QUANTITY_UNVERIFIED, DEPTH_UNKNOWN, DEPTH, LISTING_ROOM, BUDGET }
    record Decision(int units, Gate gate) {}

    static Decision plan(int stacks, int fullStack, int resaleLot, int depthStacks,
                         boolean depthLimited, int listingRoom, long unit, long budget,
                         int verifiedQuantityLimit) {
        if (stacks < 1 || stacks > 30 || fullStack < 1 || fullStack > 64 || unit <= 0 || budget < 0)
            return new Decision(0, Gate.INVALID);
        if (resaleLot != fullStack) return new Decision(0, Gate.NOT_WHOLE_STACKS);
        int units = stacks * fullStack;
        if (units > verifiedQuantityLimit) return new Decision(0, Gate.QUANTITY_UNVERIFIED);
        if (depthLimited && depthStacks < 1) return new Decision(0, Gate.DEPTH_UNKNOWN);
        if (depthLimited && depthStacks < stacks) return new Decision(0, Gate.DEPTH);
        if (listingRoom < stacks) return new Decision(0, Gate.LISTING_ROOM);
        if (units > budget / unit) return new Decision(0, Gate.BUDGET);
        return new Decision(units, Gate.READY);
    }
}

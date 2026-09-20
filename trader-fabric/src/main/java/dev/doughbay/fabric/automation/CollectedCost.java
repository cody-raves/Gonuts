package dev.doughbay.fabric.automation;

/** Conservative acquisition estimates when collection notices cover only part of a stack. */
final class CollectedCost {
    static long fullStack(int count, long unitPrice) {
        if (count <= 0 || unitPrice <= 0) throw new IllegalArgumentException("Positive quantity and unit cost required");
        return Math.multiplyExact(unitPrice, count);
    }

    static long resize(long cost, int originalCount, int physicalCount, long unitPrice) {
        if (cost <= 0 || originalCount <= 0) throw new IllegalArgumentException("Missing acquisition cost");
        long proportional = java.math.BigInteger.valueOf(cost)
                .multiply(java.math.BigInteger.valueOf(physicalCount))
                .add(java.math.BigInteger.valueOf(originalCount - 1))
                .divide(java.math.BigInteger.valueOf(originalCount)).longValueExact();
        return Math.max(proportional, fullStack(physicalCount, unitPrice));
    }

    private CollectedCost() {}
}

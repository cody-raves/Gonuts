package dev.doughbay.core.model;

/**
 * Stack sizes are not assumed to price linearly: x16, x32 and x64 pearls can be
 * genuinely different markets. Exact common sizes get their own bucket; anything
 * else falls into OTHER and is analyzed per-unit only.
 */
public enum StackBucket {
    X1(1), X16(16), X32(32), X64(64), OTHER(-1);

    private final int exactCount;

    StackBucket(int exactCount) {
        this.exactCount = exactCount;
    }

    public static StackBucket of(int count) {
        return switch (count) {
            case 1 -> X1;
            case 16 -> X16;
            case 32 -> X32;
            case 64 -> X64;
            default -> OTHER;
        };
    }

    /** -1 for OTHER. */
    public int exactCount() {
        return exactCount;
    }

    public String label() {
        return this == OTHER ? "other" : "x" + exactCount;
    }
}

package dev.doughbay.fabric.automation;

/** Exact preconditions for each split click; -1 denotes a different item/components. */
final class PeelInventoryState {
    static boolean matches(int stage, int total, int source, int destination, int cursor) {
        if (total <= 1) return false;
        return switch (stage) {
            case 0 -> source == total && destination == 0 && cursor == 0;
            case 1 -> source == 0 && destination == 0 && cursor == total;
            case 2 -> source == 0 && destination == 1 && cursor == total - 1;
            default -> false;
        };
    }
    private PeelInventoryState() {}
}

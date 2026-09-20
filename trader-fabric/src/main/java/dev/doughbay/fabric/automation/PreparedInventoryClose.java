package dev.doughbay.fabric.automation;

/** Completes the player-inventory interaction before an auction command may start. */
final class PreparedInventoryClose {
    private static final long SETTLE_MILLIS = 600;
    private boolean closed;
    private long readyAt;

    void reset() {
        closed = false;
        readyAt = 0;
    }

    /** Call only after verifying the selected stack, source slot, and empty cursor. */
    boolean ready(long now, Runnable closeInventory) {
        if (!closed) {
            closeInventory.run();
            closed = true;
            readyAt = now + SETTLE_MILLIS;
            return false;
        }
        return now >= readyAt;
    }
}

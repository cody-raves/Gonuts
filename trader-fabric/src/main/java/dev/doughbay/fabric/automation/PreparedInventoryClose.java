package dev.doughbay.fabric.automation;

/** Completes the player-inventory interaction before an auction command may start. */
final class PreparedInventoryClose {
    private static final long SETTLE_MILLIS = 600;
    private boolean closed;
    private long readyAt;
    private Object pendingOwner;

    void reset() {
        closed = false;
        readyAt = 0;
        pendingOwner = null;
    }

    /** Arm before sending a swap, so even an exception or pause must finish it. */
    void interactionStarted(Object owner) {
        reset();
        pendingOwner = java.util.Objects.requireNonNull(owner);
    }

    boolean cleanupPending() { return pendingOwner != null; }

    /** Drain before any controller recovery or new operation, even while paused. */
    boolean finishPending(Object owner, boolean safeContext, long now, Runnable closeInventory) {
        if (pendingOwner == null) return true;
        if (owner != pendingOwner) {
            // A disconnected/replaced player cannot retain the old server menu.
            reset();
            return true;
        }
        if (!safeContext || !ready(now, closeInventory)) return false;
        pendingOwner = null;
        return true;
    }

    /** Call only in the player inventory with an empty cursor; never a foreign menu. */
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

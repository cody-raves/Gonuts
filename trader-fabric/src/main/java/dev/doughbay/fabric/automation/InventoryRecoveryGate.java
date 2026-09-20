package dev.doughbay.fabric.automation;

/** A locally empty cursor is not proof of a settled server inventory. */
final class InventoryRecoveryGate {
    private boolean pending;
    private Object owner;
    private Object menu;
    private long clearSince = -1;

    void begin() { pending = true; clearSince = -1; }
    boolean pending() { return pending; }

    boolean hold(Object currentOwner, Object currentMenu, boolean carried, boolean inventoryOnly, long now) {
        if (owner != currentOwner) {
            owner = currentOwner;
            menu = currentMenu;
            clearSince = -1;
        }
        if (carried) begin();
        if (!pending) return false;
        if (carried || !inventoryOnly || menu != currentMenu) {
            menu = currentMenu;
            clearSince = -1;
            return true;
        }
        if (clearSince < 0) clearSince = now;
        if (now - clearSince < 1000) return true;
        pending = false;
        clearSince = -1;
        return false;
    }
}

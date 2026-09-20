package dev.doughbay.fabric;

/** Shared geometry for capacity cells and the item animations landing in them. */
record SlotGridLayout(int width, int slots, int columns, int gap, int cellHeight) {
    static SlotGridLayout fit(int width, int slots) {
        slots = Math.clamp(slots, 1, 90);
        int columns = Math.min(slots, slots > 45 ? 18 : 15);
        int gap = width >= columns * 8 ? 2 : 1;
        int cell = Math.max(1, (width - (columns - 1) * gap) / columns);
        return new SlotGridLayout(width, slots, columns, gap, Math.min(14, cell));
    }

    int rows() { return (slots + columns - 1) / columns; }
    int height() { return rows() * (cellHeight + gap) - gap; }
    int left(int index) { return (index % columns) * (width + gap) / columns; }
    int right(int index) { return ((index % columns) + 1) * (width + gap) / columns - gap; }
    int top(int index) { return (index / columns) * (cellHeight + gap); }
}

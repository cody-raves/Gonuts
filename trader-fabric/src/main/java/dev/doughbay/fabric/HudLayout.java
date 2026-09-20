package dev.doughbay.fabric;

import java.util.List;

/** Places a HUD surface in its chosen horizontal lane without covering other surfaces. */
final class HudLayout {
    record Rect(int x, int y, int width, int height) {
        boolean overlaps(Rect other, int gap) {
            return x < other.x + other.width + gap && x + width + gap > other.x
                    && y < other.y + other.height + gap && y + height + gap > other.y;
        }
    }

    private HudLayout() { }

    static Rect place(int x, int preferredY, int width, int height, int screenHeight,
                      int margin, List<Rect> occupied) {
        int lastY = screenHeight - margin - height;
        if (width <= 0 || height <= 0 || lastY < margin) return null;
        Rect result = scan(x, Math.max(margin, Math.min(lastY, preferredY)), width, height,
                lastY, occupied);
        return result != null ? result : scan(x, margin, width, height, lastY, occupied);
    }

    private static Rect scan(int x, int y, int width, int height, int lastY, List<Rect> occupied) {
        while (y <= lastY) {
            Rect candidate = new Rect(x, y, width, height);
            int next = y;
            for (Rect obstacle : occupied) {
                if (candidate.overlaps(obstacle, 6)) next = Math.max(next, obstacle.y + obstacle.height + 6);
            }
            if (next == y) return candidate;
            y = next;
        }
        return null;
    }
}

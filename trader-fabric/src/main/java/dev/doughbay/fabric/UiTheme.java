package dev.doughbay.fabric;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared visual language for the cockpit, HUD, and notification cards. */
final class UiTheme {
    static final int GOLD = 0xFFF4CD7A;
    static final int TEAL = 0xFF56DCCB;
    static final int TEXT = 0xFFF0EEE7;
    static final int SECONDARY = 0xFFB6C2CD;
    static final int MUTED = 0xFF8799AA;
    static final int BACKGROUND = 0xFA0C121B;
    static final int SURFACE = 0xF2182230;
    static final int INSET = 0xF0101823;
    static final int EDGE = 0xFF2C3B4C;
    static final int HOVER = 0xFF243747;
    static final int GOOD = 0xFF77DEB0;
    static final int WARN = 0xFFF3B576;
    static final int BAD = 0xFFFF858C;

    private UiTheme() { }

    static boolean reducedMotion() { return Tuning.get("hud.reduced_motion") >= 0.5; }

    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) return;
        rounded(g, x + 2, y + 3, w, h, 7, 0x50000000);
        rounded(g, x, y, w, h, 7, EDGE);
        rounded(g, x + 1, y + 1, w - 2, h - 2, 6, color);
    }

    static void rounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0) return;
        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));
        g.fill(x, y + r, x + w, y + h - r, color);
        for (int row = 0; row < r; row++) {
            double dy = r - row - 0.5;
            int inset = (int) Math.ceil(r - Math.sqrt(r * r - dy * dy));
            g.fill(x + inset, y + row, x + w - inset, y + row + 1, color);
            g.fill(x + inset, y + h - row - 1, x + w - inset, y + h - row, color);
        }
    }

    static String fit(Font font, String value, int width) {
        if (value == null || width <= 0) return "";
        if (font.width(value) <= width) return value;
        String end = "…";
        if (font.width(end) > width) return "";
        return font.plainSubstrByWidth(value, width - font.width(end)) + end;
    }
}

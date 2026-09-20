package dev.doughbay.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Draws the auction-slot grid shared by the DoughBay screen and the HUD.
 * DonutSMP grants 45 auction slots, 90 with the top rank; the grid is 15
 * wide and as tall as the slot count needs.
 */
final class SlotTracker {
    /** 15 across for 45 slots (3 rows); 18 across for 90 (5 rows), a compact rectangle either way. */
    static int columns() {
        return slots() > 45 ? 18 : 15;
    }

    /** The rank's slot count, from the live setting. */
    static int slots() {
        return Math.max(1, Math.min(90, (int) Math.round(Tuning.get("slots.max"))));
    }

    static int rows() {
        return (slots() + columns() - 1) / columns();
    }

    private static final int FILLED = 0xFF5FCF78;
    private static final int FILLED_EDGE = 0xFF2E7A40;
    private static final int ALLOWED = 0xFF3C4050;
    private static final int BEYOND_CAP = 0xFF1C1E26;

    private SlotTracker() {
    }

    /** The vanilla experience bar, which is 182 wide and notched into eighteen. */
    private static final net.minecraft.resources.Identifier XP_BACK =
            net.minecraft.resources.Identifier.withDefaultNamespace("hud/experience_bar_background");
    private static final net.minecraft.resources.Identifier XP_FILL =
            net.minecraft.resources.Identifier.withDefaultNamespace("hud/experience_bar_progress");
    private static final int XP_WIDTH = 182;
    private static final int XP_HEIGHT = 5;
    /** Notches in one bar; five bars of eighteen is exactly the ninety slots a rank grants. */
    private static final int PER_BAR = 18;

    /**
     * The slots drawn as stacked experience bars instead of a grid of cells.
     *
     * <p>The bar is already notched into eighteen and the top rank grants
     * ninety slots, so five of them stacked is the whole book with nothing left
     * over - and it is a shape the eye already reads, because it has been
     * reading it since the first time it levelled up. A hand-drawn grid of
     * little squares is a thing to learn; this is not.
     *
     * <p>Each bar fills with its own eighteen, so a glance says both how full
     * the book is and roughly where the edge of it sits.
     *
     * @return the y below the bars
     */
    /**
     * The auction slots drawn where the experience bar goes, in its place.
     *
     * <p>Centred and sitting on the hotbar exactly as the vanilla bar does, so
     * it costs no screen and needs no explaining: the shape is one this game
     * has been teaching since the first level-up. Five bars of eighteen notches
     * is ninety slots with nothing left over, and levels are worth nothing on
     * an account that only trades, so the space was going spare anyway.
     *
     * <p>The first eighteen are the top bar and it fills downward from there,
     * the same order the panel's grid uses. Building upward instead read as the
     * opposite direction to the grid beside it, so the same eighty-one slots
     * looked like two different numbers depending on which one you glanced at.
     */
    static void drawVanillaBars(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return;
        int used = Math.max(0, Math.min(slots(), listedNow()));
        int bars = Math.max(1, (slots() + PER_BAR - 1) / PER_BAR);
        int step = XP_HEIGHT + 1;
        int x = (client.getWindow().getGuiScaledWidth() - XP_WIDTH) / 2;
        int bottom = client.getWindow().getGuiScaledHeight() - 32;
        int top = bottom - (bars - 1) * step;
        for (int i = 0; i < bars; i++) {
            // Row 0 is the top bar, so the stack reads downward like the grid.
            int by = top + i * step;
            graphics.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    XP_BACK, XP_WIDTH, XP_HEIGHT, 0, 0, x, by, XP_WIDTH, XP_HEIGHT);
            int filled = Math.max(0, Math.min(PER_BAR, used - i * PER_BAR));
            if (filled <= 0) continue;
            int w = Math.max(1, Math.round(XP_WIDTH * (filled / (float) PER_BAR)));
            graphics.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    XP_FILL, XP_WIDTH, XP_HEIGHT, 0, 0, x, by, w, XP_HEIGHT);
        }
    }

    /** Listings up now, from the server's own count where it is fresh. */
    private static int listedNow() {
        var session = DoughBayClient.automationSessionController();
        if (session == null) return 0;
        int fromServer = session.serverListedSlots();
        return fromServer >= 0 ? fromServer : session.snapshot().openListings();
    }

    static int drawBars(GuiGraphicsExtractor graphics, int x, int y, int width, int used, int cap) {
        int bars = Math.max(1, (slots() + PER_BAR - 1) / PER_BAR);
        int barWidth = Math.max(20, Math.min(XP_WIDTH, width));
        int step = XP_HEIGHT + 1;
        for (int i = 0; i < bars; i++) {
            int by = y + i * step;
            graphics.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    XP_BACK, XP_WIDTH, XP_HEIGHT, 0, 0, x, by, barWidth, XP_HEIGHT);
            int filled = Math.max(0, Math.min(PER_BAR, used - i * PER_BAR));
            if (filled <= 0) continue;
            int w = Math.max(1, Math.round(barWidth * (filled / (float) PER_BAR)));
            graphics.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    XP_FILL, XP_WIDTH, XP_HEIGHT, 0, 0, x, by, w, XP_HEIGHT);
        }
        return y + bars * step;
    }

    /**
     * Draws the grid at the given position, sizing cells to the width, and
     * returns the y just below it. Cells 0..used-1 are filled, cells up to
     * cap are outlined as available, the rest are dark.
     */
    static int drawGrid(GuiGraphicsExtractor graphics, int x, int y, int width,
                        int used, int cap) {
        return drawGrid(graphics, x, y, width, used, cap, 255);
    }

    static int drawGrid(GuiGraphicsExtractor graphics, int x, int y, int width,
                        int used, int cap, int alpha) {
        return drawGrid(graphics, x, y, width, used, cap, alpha, FILLED, FILLED_EDGE);
    }

    /** The grid in another colour: blue cells for the order house, green for the auction. */
    static int drawGrid(GuiGraphicsExtractor graphics, int x, int y, int width,
                        int used, int cap, int alpha, int filled, int filledEdge) {
        return drawGrid(graphics, x, y, SlotGridLayout.fit(width, slots()), used, cap, alpha, filled, filledEdge);
    }

    static int drawGrid(GuiGraphicsExtractor graphics, int x, int y, SlotGridLayout layout,
                        int used, int cap, int alpha, int filled, int filledEdge) {
        for (int i = 0; i < layout.slots(); i++) {
            int cx = x + layout.left(i);
            int cy = y + layout.top(i);
            int right = x + layout.right(i);
            int bottom = cy + layout.cellHeight();
            if (i < used) {
                graphics.fill(cx, cy, right, bottom, withAlpha(filledEdge, alpha));
                graphics.fill(cx, cy, right - 1, bottom - 1, withAlpha(filled, alpha));
            } else if (i < cap) {
                graphics.fill(cx, cy, right, bottom, withAlpha(ALLOWED, alpha));
                graphics.fill(cx + 1, cy + 1, right - 1, bottom - 1,
                        withAlpha(BEYOND_CAP, alpha));
            } else {
                graphics.fill(cx, cy, right, bottom, withAlpha(BEYOND_CAP, alpha));
            }
        }
        return y + layout.height();
    }

    /** Width of the grid drawn at the given cell size. */
    static int gridWidth(int cell) {
        return columns() * cell + (columns() - 1);
    }

    private static int withAlpha(int argb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0x00FFFFFF);
    }
}

package dev.doughbay.fabric;

import dev.doughbay.core.model.Position;
import dev.doughbay.fabric.automation.AutomationSessionController;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/** A steady cream-and-teal head with independently animated facial features. */
public final class Mascot {
    public enum Face {
        IDLE, WATCH, WORK, SALE, PAUSED, SLEEP, ALERT;
    }
    public static final int TEXTURE_WIDTH = 1254;
    public static final int TEXTURE_HEIGHT = 1254;
    private static final Identifier SHELL = Identifier.fromNamespaceAndPath("doughbay", "textures/gui/mascot_shell.png");
    public static final int LOGO_WIDTH = 512;
    public static final int LOGO_HEIGHT = 185;
    private static final Identifier LOGO = Identifier.fromNamespaceAndPath("doughbay", "textures/gui/logo.png");
    private static final long SALE_FACE_MILLIS = 4_500;
    private static final MascotAnimation ANIMATION = new MascotAnimation();

    private static volatile long lastSaleAt;
    private static volatile long lastSaleProfit;
    private static volatile String lastSaleItem = "";
    private static Face current = Face.SLEEP;
    private static Face pending = Face.SLEEP;
    private static long pendingSince;

    private static long motionMillis() { return System.nanoTime() / 1_000_000L; }

    private Mascot() {
    }

    /** Called when one of the mod's listings sells; the face celebrates for a moment. */
    public static void noteSale(Position sold) {
        if (sold == null) return;
        lastSaleAt = System.currentTimeMillis();
        lastSaleProfit = Double.isFinite(sold.realizedProfit()) ? Math.round(sold.realizedProfit())
                : sold.salePrice() - sold.purchasePrice();
        String key = sold.itemKey() == null ? "" : sold.itemKey();
        int hash = key.indexOf('#');
        lastSaleItem = hash >= 0 ? key.substring(0, hash) : key;
    }

    /** The item of the sale the line describes, for its icon; "" when the line is not showing. */
    public static String recentSaleItemKey() {
        return System.currentTimeMillis() - lastSaleAt > SALE_FACE_MILLIS ? "" : lastSaleItem;
    }

    /** "Sold redstone +$3.2k", for a few seconds after a sale; otherwise "". */
    public static String recentSaleLine() {
        long now = System.currentTimeMillis();
        if (now - lastSaleAt > SALE_FACE_MILLIS || lastSaleItem.isEmpty()) return "";
        String name = lastSaleItem.substring(lastSaleItem.indexOf(':') + 1).replace('_', ' ');
        return "Sold " + name + "  " + (lastSaleProfit >= 0 ? "+" : "-") + "$" + compact(Math.abs(lastSaleProfit));
    }

    private static String compact(long value) {
        if (value >= 1_000_000) return String.format(Locale.ROOT, "%.1fm", value / 1_000_000.0);
        if (value >= 1_000) return String.format(Locale.ROOT, "%.1fk", value / 1_000.0);
        return String.valueOf(value);
    }

    /** Which animation fits this moment. */
    public static Face faceFor(AutomationSessionController.SessionSnapshot session, EvasionGuard evasion) {
        long now = System.currentTimeMillis();
        Face face;
        if (evasion != null && evasion.triggered()) {
            face = Face.ALERT;
        } else if (now - lastSaleAt < SALE_FACE_MILLIS) {
            face = Face.SALE;
        } else if (session == null) {
            face = Face.SLEEP;
        } else {
            face = switch (session.state()) {
                case STOPPED -> Face.IDLE;
                case PAUSED -> Face.PAUSED;
                case SCANNING, BUYING, READING_ORDERS, AUDITING_SLOTS -> Face.WATCH;
                case COOLDOWN, MONITORING -> Face.IDLE;
                default -> Face.WORK;
            };
        }
        long clock = motionMillis();
        if (face != pending) {
            pending = face;
            pendingSince = clock;
        }
        // Avoid expression flicker during quick operational state changes.
        // Stops, pauses, sales, and warnings remain immediately visible.
        boolean immediate = face == Face.ALERT || face == Face.SALE
                || face == Face.PAUSED || face == Face.SLEEP;
        if (face != current && (immediate || clock - pendingSince >= 240)) {
            current = face;
        }
        return current;
    }

    /** The shell stays still. Pupils, lids, eyebrows, mouth and reels move inside it. */
    public static int draw(GuiGraphicsExtractor graphics, Face face, int x, int y, int width) {
        var facialPose = ANIMATION.frame(MascotAnimation.Mood.valueOf(face.name()),
                motionMillis(), lastSaleAt, UiTheme.reducedMotion());
        var pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(width / 256f, width / 256f);
        graphics.blit(RenderPipelines.GUI_TEXTURED, SHELL, 0, 0, 0f, 0f,
                256, 256, TEXTURE_WIDTH, TEXTURE_HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT);
        MascotFace.draw(new MascotFace.Canvas() {
            public void fill(int l, int t, int r, int b, int color) { graphics.fill(l, t, r, b, color); }
            public void clip(int l, int t, int r, int b) { graphics.enableScissor(l, t, r, b); }
            public void unclip() { graphics.disableScissor(); }
        }, facialPose);
        pose.popMatrix();
        return width;
    }

    /** Draws the wordmark at the given height; returns the width used. */
    public static int drawLogo(GuiGraphicsExtractor graphics, int x, int y, int height) {
        int width = Math.max(1, Math.round(height * (float) LOGO_WIDTH / LOGO_HEIGHT));
        graphics.blit(RenderPipelines.GUI_TEXTURED, LOGO, x, y, 0f, 0f,
                width, height, LOGO_WIDTH, LOGO_HEIGHT, LOGO_WIDTH, LOGO_HEIGHT);
        return width;
    }
}

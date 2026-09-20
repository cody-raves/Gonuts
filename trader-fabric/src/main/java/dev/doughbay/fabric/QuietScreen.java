package dev.doughbay.fabric;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * Turns the screen down while the bot works.
 *
 * <p>A trading bot is a slideshow. It opens the auction house, searches, walks
 * pages, clicks, closes, and does it again a few hundred times an hour, and
 * every one of those flashes a container up over whatever you were looking at.
 * The hotbar, the hunger bar and the chat scroll underneath it the whole time.
 * None of it is for you - it is the bot's workbench, drawn on your monitor
 * because that is where the game happens to render.
 *
 * <p>Nothing here changes what the bot does. It never uses the mouse: slots are
 * clicked by calling the container handler with a slot number, and chat is read
 * off the network as it arrives. Both work exactly as well with nothing drawn,
 * so what is hidden here is only ever the picture, never the machinery.
 *
 * <p>Everything is off by default and everything comes back while the peek key
 * is held, because a bot you cannot see is a bot you cannot check.
 */
public final class QuietScreen {
    private static volatile boolean peeking;

    private QuietScreen() {
    }

    /** True while the operator is holding the peek key; nothing hides then. */
    public static boolean peeking() {
        return peeking;
    }

    public static void setPeeking(boolean value) {
        peeking = value;
    }

    private static boolean on(String key) {
        return !peeking && Tuning.get(key) >= 0.5;
    }

    /**
     * Whether a container the bot opened should be drawn.
     *
     * <p>Only the bot's own screens are hidden, and only while it is actually
     * driving one: an inventory you opened yourself is yours to look at.
     */
    public static boolean hideContainer() {
        if (!on("quiet.hide_containers")) return false;
        // Any running session's containers are the bot's workbench, not busy in
        // particular: the server pushes the auction page back open between steps,
        // and a recovered session reads the order house before it re-arms, so
        // gating on "armed" alone let the orders page flash through. A genuinely
        // paused or stopped session is left visible so it can be inspected.
        return DoughBayClient.automationSessionController().drivingOwnContainers();
    }

    /** Health and hunger are worth seeing when they matter and not before. */
    private static boolean vitalsWorthShowing(Minecraft client) {
        if (!on("quiet.vitals_when_low")) return true;
        Player player = client == null ? null : client.player;
        if (player == null) return true;
        double at = Tuning.get("quiet.vitals_pct") / 100.0;
        return player.getHealth() / Math.max(1f, player.getMaxHealth()) <= at
                || player.getFoodData().getFoodLevel() / 20.0 <= at;
    }

    /**
     * Wraps the vanilla elements rather than removing them, so every one can
     * come back the moment a setting changes or the peek key goes down. A
     * removed element would need a restart to return.
     */
    static void register() {
        HudElementRegistry.replaceElement(VanillaHudElements.HOTBAR, wrapped -> (graphics, tick) -> {
            if (on("quiet.hide_hotbar")) return;
            wrapped.extractRenderState(graphics, tick);
        });
        // The bar itself is the contextual info bar, not EXPERIENCE_LEVEL -
        // that is only the number floating above it, which is why replacing it
        // drew nothing where the bar was.
        HudElementRegistry.replaceElement(VanillaHudElements.INFO_BAR, wrapped -> (graphics, tick) -> {
            // It sits where the eye already looks and is already notched into
            // eighteen. Five stacked is ninety slots exactly - the whole book,
            // in the game's own furniture, with no legend to learn. Levels are
            // worth nothing on an account that only trades, so the space was
            // going spare.
            if (Tuning.get("hud.xp_bars") >= 0.5 && !peeking) {
                SlotTracker.drawVanillaBars(graphics);
                return;
            }
            if (on("quiet.hide_hotbar")) return;
            wrapped.extractRenderState(graphics, tick);
        });
        HudElementRegistry.replaceElement(VanillaHudElements.EXPERIENCE_LEVEL, wrapped -> (graphics, tick) -> {
            if (on("quiet.hide_hotbar") || (Tuning.get("hud.xp_bars") >= 0.5 && !peeking)) return;
            wrapped.extractRenderState(graphics, tick);
        });
        HudElementRegistry.replaceElement(VanillaHudElements.HEALTH_BAR, wrapped -> (graphics, tick) -> {
            if (on("quiet.hide_hotbar") && vitalsWorthShowing(Minecraft.getInstance())) {
                wrapped.extractRenderState(graphics, tick);
                return;
            }
            if (!vitalsWorthShowing(Minecraft.getInstance())) return;
            wrapped.extractRenderState(graphics, tick);
        });
        HudElementRegistry.replaceElement(VanillaHudElements.FOOD_BAR, wrapped -> (graphics, tick) -> {
            if (!vitalsWorthShowing(Minecraft.getInstance())) return;
            wrapped.extractRenderState(graphics, tick);
        });
        HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, wrapped -> (graphics, tick) -> {
            // Nothing here aims at anything. The bot clicks slots by number
            // and never uses the mouse at all, so the crosshair is a dot
            // pinned to the middle of a view it has no part in - and once the
            // camera is drifting, a fixed dot over a moving picture is the
            // one thing that gives away it is not a person playing.
            if (on("quiet.hide_crosshair")) return;
            wrapped.extractRenderState(graphics, tick);
        });
        HudElementRegistry.replaceElement(VanillaHudElements.HELD_ITEM_TOOLTIP, wrapped -> (graphics, tick) -> {
            // The name that flashes up whenever the held slot changes. The bot
            // changes it every few seconds to reach a stack, so this is the
            // hotbar's flicker rather than a label anybody reads.
            if (on("quiet.hide_hotbar")) return;
            wrapped.extractRenderState(graphics, tick);
        });
        HudElementRegistry.replaceElement(VanillaHudElements.CHAT, wrapped -> (graphics, tick) -> {
            // The bot reads chat off the network, not off the screen, so this
            // costs it nothing at all - the sale receipts still arrive and are
            // still parsed with the chat box drawing nothing.
            if (on("quiet.hide_chat")) return;
            wrapped.extractRenderState(graphics, tick);
        });
    }
}

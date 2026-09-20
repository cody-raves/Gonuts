package dev.doughbay.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Asks the one question none of the other guards ask: is anything actually
 * happening?
 *
 * <p>Every safety check in this mod is oriented against loss. The page is not
 * clicked unless it is the right page, a purchase is not booked unless the
 * inventory agrees, nothing trades while the ledger is in doubt. They all held
 * through a night of trouble and no money was lost to any of it.
 *
 * <p>What none of them asks is whether the session is getting anywhere. A
 * trident that would not go into the reserved slot span for six thousand
 * ledger rows; the bid desk refused its own queue for days; twelve pull-backs
 * opened the wrong chest and gave up. Each of those was silence, not an error,
 * and silence is what nothing was watching for.
 *
 * <p>So this looks at outcomes rather than steps: stock is held, the session
 * says it is running, and yet nothing has sold and nothing has been listed for
 * a long time. That is worth saying out loud, once, rather than leaving it to
 * be noticed on a HUD hours later.
 */
final class Liveness {
    /** Nothing sold and nothing listed for this long, while holding stock, is a stall. */
    private static long quietMillis() {
        return (long) (Tuning.get("liveness.quiet_min") * 60_000L);
    }

    private long lastProgressAt = System.currentTimeMillis();
    private long lastComplaintAt;
    private long lastHealAt;
    private long lastCheckAt;
    private long lastTraceAt;
    private int lastSales = -1;
    private int lastListed = -1;
    /** The last thing this check concluded, for the Status page and the log. */
    private volatile String verdict = "not looked yet";

    /**
     * What the stall watch currently believes, in a few words.
     *
     * <p>Added because the check failed silently: a client sat for
     * thirty-seven minutes against a twenty-five minute threshold, every
     * condition for complaining apparently met, and said nothing - and there
     * was no way to tell whether it had decided not to complain or had never
     * run at all. A guard that cannot be observed cannot be trusted, and this
     * one had exactly one job.
     */
    String describe() {
        return verdict;
    }

    /**
     * <p>{@code sales} must be sales that <em>completed</em>. It was fed the
     * session's trades-started count, and the difference is the whole point:
     * a desk churning through decisions it can never finish starts trades
     * constantly and completes none, so that number climbed all through a
     * thirty-seven minute stall, the check saw movement on every pass, and the
     * timer never left zero. The one failure this exists to catch was the one
     * it was blind to.
     *
     * @param sales   sales that have completed, monotonically increasing
     * @param listed  how many of our listings are up
     * @param holding whether there is stock or money committed that ought to move
     * @param running whether the session believes it is trading
     */
    /**
     * Says what it is seeing every few minutes whether or not it is worried.
     *
     * <p>A watch that only speaks when it is unhappy is indistinguishable from
     * a watch that is not running.
     */
    private void trace(long now, int sales, int listed, boolean holding, boolean running,
            String state, long still) {
        if (now - lastTraceAt < 5 * 60_000L) return;
        lastTraceAt = now;
        DoughBayClient.LOGGER.info(
                "DoughBay liveness: {} (sales {}, listed {}, holding {}, running {}, state {})",
                verdict, sales, listed, holding, running, state);
    }

    /**
     * Closes a stray auction page or dialog the way {@code closeAuctionScreen}
     * does, and only those: a container is closed the server-clean way, a
     * dialog is dismissed. Any other screen - the pause menu, the mod's own
     * config, the title screen - is left untouched, so a stall that is not a
     * stray page is reported rather than meddled with.
     *
     * @return true only if a screen was actually there to close
     */
    private static boolean tryEscapeStrayScreen(Minecraft client) {
        if (client == null || client.player == null) return false;
        Screen open = client.gui.screen();
        if (open instanceof AbstractContainerScreen<?>) {
            client.player.closeContainer();
            return true;
        }
        if (open instanceof DialogScreen<?>) {
            client.gui.setScreen(null);
            return true;
        }
        return false;
    }

    void observe(Minecraft client, int sales, int listed, boolean holding, boolean running, String state) {
        long now = System.currentTimeMillis();
        if (now - lastCheckAt < 15_000L) return;
        lastCheckAt = now;
        if (Tuning.get("liveness.enabled") < 0.5) {
            verdict = "off";
            return;
        }

        // Any of these means the machine is turning over; a listing count that
        // moves either way is progress, because something sold or went up.
        boolean moved = sales != lastSales || listed != lastListed;
        lastSales = sales;
        lastListed = listed;
        if (moved || !running || !holding) {
            lastProgressAt = now;
            verdict = !running ? "idle: the session is not trading"
                    : !holding ? "nothing held to move"
                    : "moving: " + sales + " sold, " + listed + " listed";
            trace(now, sales, listed, holding, running, state, 0);
            return;
        }

        long still = now - lastProgressAt;
        verdict = "still for " + (still / 60_000L) + " min of " + (quietMillis() / 60_000L);
        trace(now, sales, listed, holding, running, state, still);
        if (still < quietMillis()) return;

        // Hands, not just a voice: the usual stall is a stray auction page the
        // server reopened, and closing it is exactly the Escape a person would
        // press to break the loop out. Only ever closes an open container or
        // dialog - never a trade mid-flight, never the pause menu or a config
        // screen - and retries every couple of minutes in case a fresh stray
        // page appears. Off by default because it acts on the live session.
        if (Tuning.get("liveness.self_heal") >= 0.5 && now - lastHealAt >= 2 * 60_000L) {
            if (tryEscapeStrayScreen(client)) {
                lastHealAt = now;
                lastProgressAt = now;
                verdict = "self-heal: closed a stray screen to break the stall";
                DoughBayClient.LOGGER.warn(
                        "DoughBay liveness: self-heal closed a stray screen after {} min stalled (state {})",
                        still / 60_000L, state);
            }
        }

        // Said once per stall, not once per tick, and not again for an hour.
        if (now - lastComplaintAt < Math.max(quietMillis(), 60 * 60_000L)) return;
        lastComplaintAt = now;

        String message = String.format(Locale.ROOT,
                "Nothing has sold or been listed for %d minutes while holding %d listing(s); the session says %s",
                still / 60_000L, listed, state);
        DoughBayClient.LOGGER.warn("DoughBay liveness: {}", message);
        if (client != null && client.player != null) {
            client.player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal("[GoNuts] " + message));
        }
        try {
            StatusWebhook hook = DoughBayClient.webhook();
            if (hook != null && Tuning.get("liveness.alert") >= 0.5) {
                List<String[]> lines = new ArrayList<>();
                lines.add(new String[] {"still for", (still / 60_000L) + " min"});
                lines.add(new String[] {"listings up", String.valueOf(listed)});
                lines.add(new String[] {"session state", state});
                hook.alert("Nothing is moving", 0xE8B04B, lines, "");
            }
        } catch (Throwable ignored) {
            // a stall is worth saying in the log even if Discord cannot be told
        }
    }
}

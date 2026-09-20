package dev.doughbay.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Walks the client from the title screen onto the server by itself.
 *
 * <p>An unattended trader that gets kicked, crashes or is restarted is worth
 * nothing while it sits on the title screen, and the only thing standing
 * between it and the market is two clicks. This does those two clicks: it
 * finds the server in the list, adds it if it is not there, and connects.
 *
 * <p>It waits a few seconds first, and it stops the moment any other screen
 * is opened, so a person who launched the game to do something else is never
 * dragged onto the server. It is off until it is switched on.
 *
 * <p>It cannot help with a stale login. The Microsoft token is the launcher's
 * business, and a launcher that has been open since before the token expired
 * hands the game a dead one however many times the game is relaunched; the
 * server then answers "Invalid session" and no amount of clicking fixes it.
 */
final class AutoJoin {
    private final Minecraft client;

    /** When the screen we are willing to join from first appeared. */
    private long waitingSince;
    /** The screen instance we started waiting on, so a new screen restarts the wait. */
    private Screen waitingOn;
    /** When the last attempt went out, for the retry gap. */
    private long attemptedAt;
    private int attempts;

    AutoJoin(Minecraft client) {
        this.client = client;
    }

    void tick() {
        if (Tuning.get("join.auto") < 0.5) {
            reset();
            return;
        }
        // In a world already: nothing to do, and the next disconnect starts
        // counting from scratch rather than from a stale attempt.
        if (client.level != null || client.getConnection() != null) {
            reset();
            return;
        }
        Screen screen = client.gui.screen();
        if (!joinableFrom(screen)) {
            // ConnectScreen means a connection is in flight; anything else
            // means somebody is using the game. Either way, hands off.
            waitingOn = null;
            return;
        }
        long now = System.currentTimeMillis();
        if (screen != waitingOn) {
            waitingOn = screen;
            waitingSince = now;
        }
        long waitMillis = (long) (Tuning.get("join.delay_sec") * 1000);
        if (now - waitingSince < waitMillis) return;
        long retryMillis = (long) (Tuning.get("join.retry_sec") * 1000);
        if (attemptedAt > 0 && now - attemptedAt < retryMillis) return;

        String address = address();
        if (address.isEmpty()) return;
        attemptedAt = now;
        attempts++;
        try {
            connect(address);
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay auto join failed: {}", e.toString());
        }
    }

    /** The address to join: the setting, or the last server this client was on. */
    private String address() {
        String configured = Tuning.text("join.address").strip();
        if (!configured.isEmpty()) return configured;
        ServerData last = client.getCurrentServer();
        return last == null ? "" : last.ip;
    }

    private void connect(String address) {
        ServerList list = new ServerList(client);
        list.load();
        ServerData entry = list.get(address);
        if (entry == null) {
            // Not in the list. Adding it is the point: a fresh instance, or a
            // profile that never had the server saved, should still get there.
            entry = new ServerData("DonutSMP", address, ServerData.Type.OTHER);
            list.add(entry, true);
            list.save();
            DoughBayClient.LOGGER.info("DoughBay auto join added {} to the server list", address);
        }
        DoughBayClient.LOGGER.info("DoughBay auto join: connecting to {} (attempt {})", address, attempts);
        ConnectScreen.startConnecting(client.gui.screen(), client,
                ServerAddress.parseString(address), entry, false, null);
    }

    private static boolean joinableFrom(Screen screen) {
        return screen instanceof TitleScreen
                || screen instanceof JoinMultiplayerScreen
                || screen instanceof DisconnectedScreen;
    }

    private void reset() {
        waitingOn = null;
        attemptedAt = 0;
        attempts = 0;
    }
}

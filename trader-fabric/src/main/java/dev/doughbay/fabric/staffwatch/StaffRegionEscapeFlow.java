package dev.doughbay.fabric.staffwatch;

import java.util.List;
import java.util.function.LongSupplier;

/** One local sighting -> hop; same watcher after arrival -> hop then cooldown. */
public final class StaffRegionEscapeFlow {
    public interface Client {
        boolean connected();
        boolean pauseSafely();
        List<String> regions();
        String regionHint();
        boolean requestRegion(String region);
        boolean commandSent();
        boolean transferConfirmed();
        void clearDetections();
        void resume();
        void release();
        void disconnect(String watcher, String reason);
        void notice(String watcher, String detail);
    }
    public enum Phase { IDLE, PREPARING_FIRST, TRANSFERRING_FIRST, WATCHING,
        PREPARING_SECOND, TRANSFERRING_SECOND }
    private static final long PREPARE_TIMEOUT = 15_000, TRANSFER_TIMEOUT = 30_000;
    private static final long WATCH_WINDOW = 10 * 60_000;
    private final Client client;
    private final LongSupplier clock;
    private Phase phase = Phase.IDLE;
    private String watcher = "", lastRegion = "";
    private long deadline;
    public StaffRegionEscapeFlow(Client client, LongSupplier clock) {
        this.client = client; this.clock = clock;
    }
    public Phase phase() { return phase; }
    public boolean ownsConnection() { return phase != Phase.IDLE && phase != Phase.WATCHING; }
    public boolean alert(String name, boolean enabled) {
        if (!enabled || name == null || name.isBlank() || !client.connected()) return false;
        if (phase == Phase.WATCHING && clock.getAsLong() >= deadline) cancel();
        if (phase == Phase.IDLE) {
            watcher = name;
            phase = Phase.PREPARING_FIRST;
        } else if (phase == Phase.WATCHING) {
            if (watcher.equalsIgnoreCase(name)) phase = Phase.PREPARING_SECOND;
            else { watcher = name; phase = Phase.PREPARING_FIRST; }
        } else return false; // Duplicates during the hop never escalate.
        deadline = clock.getAsLong() + PREPARE_TIMEOUT;
        client.notice(watcher, phase == Phase.PREPARING_FIRST
            ? "Preparing a safe region change" : "Same staff detected after region change; preparing final hop");
        return true;
    }
    public void tick(boolean enabled) {
        if (!enabled) { cancel(); return; }
        if (phase == Phase.IDLE) return;
        if (phase == Phase.WATCHING) {
            if (!client.connected() || clock.getAsLong() >= deadline) cancel();
            return;
        }
        if (!client.connected()) { fallback("Connection lost during region escape"); return; }
        if (clock.getAsLong() >= deadline) { fallback("Region escape timed out"); return; }
        switch (phase) {
            case PREPARING_FIRST, PREPARING_SECOND -> {
                if (!client.pauseSafely()) return;
                String exclude = lastRegion.isBlank() ? client.regionHint() : lastRegion;
                String next = chooseRegion(client.regions(), exclude);
                if (next.isEmpty()) return; // Discovery may still be pending; timeout is bounded.
                boolean second = phase == Phase.PREPARING_SECOND;
                client.clearDetections();
                if (!client.requestRegion(next)) { fallback("Region command unavailable or blocked"); return; }
                lastRegion = next;
                phase = second ? Phase.TRANSFERRING_SECOND : Phase.TRANSFERRING_FIRST;
                deadline = clock.getAsLong() + TRANSFER_TIMEOUT;
                client.notice(watcher, "Changing region: " + next);
            }
            case TRANSFERRING_FIRST, TRANSFERRING_SECOND -> {
                if (!client.commandSent() || !client.transferConfirmed()) return;
                client.clearDetections(); // Accept only fresh post-arrival probes and observations.
                if (phase == Phase.TRANSFERRING_SECOND) {
                    fallback("Final region transfer confirmed; disconnecting for 5–10 minutes");
                } else {
                    phase = Phase.WATCHING;
                    deadline = clock.getAsLong() + WATCH_WINDOW;
                    client.notice(watcher, "Region transfer confirmed; monitoring for a fresh sighting");
                    client.resume();
                }
            }
            default -> { }
        }
    }
    static String chooseRegion(List<String> regions, String exclude) {
        if (regions == null) return "";
        for (String candidate : List.of("west", "east", "asia", "eu west", "eu central", "oceania"))
            if (!candidate.equalsIgnoreCase(exclude) && regions.contains(candidate)) return candidate;
        return "";
    }
    private void fallback(String reason) {
        String who = watcher;
        client.notice(who, reason);
        // Release only after disconnect has taken ownership; never resume trading here.
        client.disconnect(who, reason);
        cancel();
    }
    public void cancel() {
        phase = Phase.IDLE; watcher = ""; lastRegion = ""; deadline = 0;
        client.release();
    }
}

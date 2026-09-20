package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The order-house floor probe: a generic experiment that posts a ladder of buy
 * orders for one item at descending prices, then cancels whatever did not fill.
 *
 * <p>It answers one question for any market: how low will the (usually
 * automated) suppliers still fill an order? Post ten single-unit orders at ten
 * prices, watch which fill and by whom (the delivery receipt names them), and
 * the price where fills stop is the floor. An unfilled order costs nothing —
 * cancel it — so the whole thing is a near-free measurement.
 *
 * <p>Off unless {@code config/doughbay/probe.json} exists with {@code enabled:
 * true}. It reads its ladder from that file, so a run is edited, not compiled.
 * Editing the file re-arms the probe; a completed run disarms itself.
 *
 * <pre>
 * { "enabled": true, "client": "your-username",
 *   "item": "minecraft:silence_armor_trim_smithing_template",
 *   "quantity": 1, "ttlMinutes": 45,
 *   "prices": [58000,56000,54000,52000,50000,48000,46000,44000,42000,40000] }
 * </pre>
 */
public final class Probe {
    /** No probe ever posts more rungs than this, whatever the file says. */
    private static final int MAX_RUNGS = 20;
    /** A probe is a measurement, not accumulation: small quantities only. */
    private static final int MAX_QUANTITY = 8;

    private static volatile Probe instance;

    private final Path file;
    private long mtime = -1;

    private boolean enabled;
    private String client = "";
    private String item = "";
    private int quantity = 1;
    private int ttlMinutes = 45;
    private List<Long> prices = List.of();

    private boolean placed;
    private long placedAt;
    private boolean done;

    private Probe(Path configDir) {
        this.file = configDir.resolve("probe.json");
        reload();
    }

    /** Loaded once at startup beside the other config, like {@link Tuning}. */
    public static void load(Path configDir) {
        instance = new Probe(configDir);
    }

    public static Probe get() {
        return instance;
    }

    /** Re-reads the file when it changes; a change re-arms a finished probe. */
    public void reload() {
        try {
            if (!Files.exists(file)) {
                enabled = false;
                return;
            }
            long m = Files.getLastModifiedTime(file).toMillis();
            if (m == mtime) return;
            mtime = m;
            JsonNode r = new ObjectMapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
            enabled = r.path("enabled").asBoolean(false);
            client = r.path("client").asText("");
            item = r.path("item").asText("");
            quantity = r.path("quantity").asInt(1);
            ttlMinutes = r.path("ttlMinutes").asInt(45);
            List<Long> ps = new ArrayList<>();
            for (JsonNode p : r.path("prices")) {
                long v = p.asLong(0);
                if (v > 0) ps.add(v);
            }
            prices = List.copyOf(ps);
            // A fresh edit re-arms the probe so the same file can run again.
            placed = false;
            done = false;
            DoughBayClient.LOGGER.info("DoughBay probe: loaded {} rung(s) for {} qty {} ttl {}m (enabled={})",
                    prices.size(), item, quantity, ttlMinutes, enabled);
        } catch (Exception e) {
            enabled = false;
            DoughBayClient.LOGGER.warn("DoughBay probe could not be read from {}: {}", file, e.toString());
        }
    }

    /** Whether this client should run the probe right now, within all caps. */
    public boolean armedFor(String account) {
        return enabled && !done
                && !item.isBlank() && !prices.isEmpty() && prices.size() <= MAX_RUNGS
                && quantity >= 1 && quantity <= MAX_QUANTITY
                && (client.isBlank() || client.equalsIgnoreCase(account == null ? "" : account));
    }

    public boolean placed() {
        return placed;
    }

    public void markPlaced(long now) {
        placed = true;
        placedAt = now;
    }

    public boolean ttlExpired(long now) {
        return placed && now - placedAt > ttlMinutes * 60_000L;
    }

    /** Disarms after a run so it does not loop; a file edit re-arms it. */
    public void markDone() {
        done = true;
    }

    public String item() {
        return item;
    }

    public int quantity() {
        return quantity;
    }

    public List<Long> prices() {
        return prices;
    }
}

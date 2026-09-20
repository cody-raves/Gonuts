package dev.doughbay.fabric;

import dev.doughbay.storage.Database;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Locale;

/**
 * An hourly note to a Discord webhook: what the trader did, and what it is
 * looking at.
 *
 * <p>Deliberately small. It reads the ledger the session already writes, posts
 * one message, and goes back to sleep. No key, no balance the player did not
 * ask to publish, and nothing that could not be read from the HUD.
 *
 * <p>The URL is a webhook, not the Discord bot: a webhook needs no gateway, no
 * token and no permissions, and if it leaks the worst anyone can do is post to
 * one channel.
 */
public final class StatusWebhook {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private final Path databasePath;
    private volatile long nextPostAt;

    public StatusWebhook(Path databasePath) {
        this.databasePath = databasePath;
        // The first note goes out an interval from now, not the moment the
        // game opens: a report of the ten seconds since launch says nothing.
        this.nextPostAt = System.currentTimeMillis() + intervalMillis();
    }

    private static long intervalMillis() {
        return Math.max(60_000L, Math.round(Tuning.get("webhook.every_min") * 60_000.0));
    }

    private static String url() {
        String u = Tuning.text("webhook.url");
        return u == null ? "" : u.strip();
    }

    /** Called from the client tick; posts at most once an interval, off-thread. */
    public void tick() {
        if (Tuning.get("webhook.enabled") < 0.5) return;
        String url = url();
        if (!url.startsWith("https://")) return;
        long now = System.currentTimeMillis();
        if (now < nextPostAt) return;
        nextPostAt = now + intervalMillis();
        // One report per hive. The queries below already read the whole shared
        // ledger, so every bot on it would post the same hive-wide numbers -
        // three bots, three identical hourly notes. When webhook.hive_one is on
        // and this is a hive, only the elected host posts (the same election
        // that decides who runs the Discord embed), so the channel gets a
        // single consolidated report. Event alerts stay per-bot; a death or an
        // escape belongs to the bot it happened to.
        if (Tuning.get("webhook.hive_one") >= 0.5
                && Tuning.get("multi.enabled") >= 0.5
                && !DoughBayClient.hostsDiscord()) {
            return;
        }
        Thread t = new Thread(() -> post(url), "doughbay-webhook");
        t.setDaemon(true);
        t.start();
    }

    /**
     * An alert, sent the moment something happens rather than on the hour.
     *
     * <p>A death or an escape is worth knowing about while it is still true.
     * When a player caused it their face is shown beside the reason, and
     * whatever they were carrying is listed: that is the difference between
     * "somebody walked past" and "somebody in full netherite came for us".
     *
     * @param title  the headline, e.g. "Escaped: SomePlayer came too close"
     * @param colour a Discord embed colour, decimal
     * @param lines  label and value pairs, drawn as fields
     * @param player the player to show a face for, or "" for none
     */
    public void alert(String title, int colour, java.util.List<String[]> lines, String player) {
        if (Tuning.get("webhook.enabled") < 0.5 || Tuning.get("webhook.alerts") < 0.5) return;
        String url = url();
        if (!url.startsWith("https://")) return;
        Thread t = new Thread(() -> {
            try {
                StringBuilder fields = new StringBuilder();
                for (String[] line : lines) {
                    if (fields.length() > 0) fields.append(',');
                    fields.append("{\"name\":\"").append(escape(line[0]))
                            .append("\",\"value\":\"").append(escape(line[1]))
                            .append("\",\"inline\":true}");
                }
                StringBuilder embed = new StringBuilder();
                embed.append("{\"title\":\"").append(escape(title)).append("\"")
                        .append(",\"color\":").append(colour)
                        .append(",\"fields\":[").append(fields).append(']');
                if (player != null && !player.isBlank()) {
                    // A public username and a face; nothing private leaves here.
                    embed.append(",\"thumbnail\":{\"url\":\"https://mc-heads.net/avatar/")
                            .append(escape(player)).append("/64\"}");
                }
                embed.append(",\"footer\":{\"text\":\"").append(escape(who())).append("\"}}");
                String body = "{\"embeds\":[" + embed + "]}";
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    DoughBayClient.LOGGER.warn("DoughBay webhook alert: Discord answered {} ({})",
                            response.statusCode(), response.body());
                }
            } catch (Exception e) {
                DoughBayClient.LOGGER.warn("DoughBay webhook alert failed: {}", e.toString());
            }
        }, "doughbay-webhook-alert");
        t.setDaemon(true);
        t.start();
    }

    private static String escape(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> { }
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    /** Posts one report now, whatever the schedule says; used by the test button. */
    public void postNow() {
        String url = url();
        if (!url.startsWith("https://")) return;
        Thread t = new Thread(() -> post(url), "doughbay-webhook-now");
        t.setDaemon(true);
        t.start();
    }

    private void post(String url) {
        try {
            // A picture reads better on a phone than a wall of monospace, and
            // the renderer the Discord bot uses is already here. Text is the
            // fallback when drawing fails, so a report always arrives.
            byte[] png = null;
            if (Tuning.get("webhook.image") >= 0.5) {
                try {
                    png = dev.doughbay.fabric.discord.StatsImage.render(page());
                } catch (Throwable t) {
                    DoughBayClient.LOGGER.warn("DoughBay webhook could not draw the report: {}", t.toString());
                }
            }
            HttpRequest request = png != null ? multipart(url, png)
                    : HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json(compose()), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                DoughBayClient.LOGGER.warn("DoughBay webhook: Discord answered {} ({})",
                        response.statusCode(), response.body());
            } else {
                DoughBayClient.LOGGER.info("DoughBay webhook: hourly report posted");
            }
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay webhook could not post: {}", e.toString());
        }
    }

    /** The same report as a drawn page, ready for the image renderer. */
    private dev.doughbay.fabric.discord.StatsImage.Page page() {
        long now = System.currentTimeMillis();
        long hour = 3_600_000L;
        java.util.List<String[]> headline = new java.util.ArrayList<>();
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        int listed = 0;
        try (Database db = new Database(databasePath)) {
            long[] lastHour = sales(db, now - hour, now);
            long[] day = sales(db, now - 24 * hour, now);
            long paidHour = payroll(db, now - hour, now);
            long paidDay = payroll(db, now - 24 * hour, now);

            headline.add(new String[] {"PROFIT, HOUR", money(lastHour[3])});
            headline.add(new String[] {"PROFIT, DAY", money(day[3])});
            headline.add(new String[] {"MARGIN", lastHour[1] > 0
                    ? String.format(Locale.ROOT, "%.1f%%", lastHour[3] * 100.0 / lastHour[1]) : "-"});

            rows.add(new String[] {"trades", lastHour[0] + " sold", day[0] + " sold"});
            rows.add(new String[] {"bought", String.valueOf(lastHour[2]), String.valueOf(day[2])});
            rows.add(new String[] {"revenue", money(lastHour[1]), money(day[1])});
            rows.add(new String[] {"payments", money(paidHour), money(paidDay)});
            rows.add(new String[] {"payroll share",
                    lastHour[3] > 0 ? String.format(Locale.ROOT, "%.0f%%", paidHour * 100.0 / lastHour[3]) : "-",
                    day[3] > 0 ? String.format(Locale.ROOT, "%.0f%%", paidDay * 100.0 / day[3]) : "-"});
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT COUNT(*), COALESCE(SUM(target_price),0) FROM positions "
                            + "WHERE mode='REAL' AND status='LISTED'");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    listed = rs.getInt(1);
                    rows.add(new String[] {"listed", String.valueOf(listed), "asking " + money(rs.getLong(2))});
                }
            }
        } catch (Exception e) {
            rows.add(new String[] {"ledger", "unreadable", String.valueOf(e)});
        }
        rows.add(new String[] {"players", population(), ""});
        rows.add(new String[] {"rivals", rivals(), ""});

        int slots = (int) Tuning.get("slots.max");
        return new dev.doughbay.fabric.discord.StatsImage.Page(
                who(),
                java.time.LocalTime.now().withNano(0) + "  -  hourly report",
                "#74E48B", headline, listed, Math.max(slots, listed),
                new String[] {"", "last hour", "last 24 h"},
                new boolean[] {false, true, true}, rows,
                "DoughBay");
    }

    /** Discord takes an image as a file part beside the JSON payload. */
    private HttpRequest multipart(String url, byte[] png) throws Exception {
        String boundary = "----DoughBay" + java.util.UUID.randomUUID();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        String payload = "{\"attachments\":[{\"id\":0,\"filename\":\"status.png\"}]}";
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(payload.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; "
                + "filename=\"status.png\"\r\nContent-Type: image/png\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(png);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
                .build();
    }

    /** The report itself, in the order it reads best. */
    private String compose() {
        StringBuilder out = new StringBuilder();
        long now = System.currentTimeMillis();
        long hour = 3_600_000L;
        try (Database db = new Database(databasePath)) {
            long[] lastHour = sales(db, now - hour, now);
            long[] day = sales(db, now - 24 * hour, now);

            out.append("**").append(who()).append("** · last hour\n");
            out.append(line("trades", lastHour[0] + " sold, " + lastHour[2] + " bought"));
            out.append(line("revenue", money(lastHour[1])));
            out.append(line("profit", money(lastHour[3])
                    + (lastHour[1] > 0
                    ? String.format(Locale.ROOT, "  (%.1f%% margin)", lastHour[3] * 100.0 / lastHour[1])
                    : "")));
            long paid = payroll(db, now - hour, now);
            out.append(line("payments", money(paid)
                    + (lastHour[3] > 0
                    ? String.format(Locale.ROOT, "  (%.0f%% of profit)", paid * 100.0 / lastHour[3])
                    : "")));

            out.append("\n**last 24 hours**\n");
            out.append(line("trades", day[0] + " sold"));
            out.append(line("profit", money(day[3])));
            out.append(line("payments", money(payroll(db, now - 24 * hour, now))));

            out.append("\n**right now**\n");
            out.append(line("open", openListings(db)));
            out.append(line("players", population()));
            out.append(line("rivals", rivals()));
        } catch (Exception e) {
            out.append("could not read the ledger: ").append(e);
        }
        return out.toString();
    }

    private static String who() {
        try {
            String name = net.minecraft.client.Minecraft.getInstance().getUser().getName();
            return name == null || name.isBlank() ? "DoughBay" : name;
        } catch (Throwable t) {
            return "DoughBay";
        }
    }

    private static String line(String label, String value) {
        return "`" + String.format(Locale.ROOT, "%-9s", label) + "` " + value + "\n";
    }

    /** {sold, revenue, bought, profit} between two moments. */
    private long[] sales(Database db, long from, long to) throws Exception {
        long[] out = new long[4];
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT COUNT(*), COALESCE(SUM(sale_price),0), COALESCE(SUM(realized_profit),0) "
                        + "FROM positions WHERE mode='REAL' AND status='SOLD' AND closed_at>? AND closed_at<=?")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    out[0] = rs.getLong(1);
                    out[1] = rs.getLong(2);
                    out[3] = Math.round(rs.getDouble(3));
                }
            }
        }
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT COUNT(*) FROM positions WHERE mode='REAL' AND purchased_at>? AND purchased_at<=?")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) out[2] = rs.getLong(1);
            }
        }
        return out;
    }

    private long payroll(Database db, long from, long to) throws Exception {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT COALESCE(SUM(amount),0) FROM payroll_payments "
                        + "WHERE status NOT IN ('FAILED','UNPAID','CARRIED') AND requested_at>? AND requested_at<=?")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private String openListings(Database db) throws Exception {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT COUNT(*), COALESCE(SUM(target_price),0) FROM positions "
                        + "WHERE mode='REAL' AND status='LISTED'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "none";
                return rs.getLong(1) + " listed, asking " + money(rs.getLong(2));
            }
        }
    }

    private static String population() {
        try {
            PopulationModel model = DoughBayClient.population();
            if (model == null) return "unknown";
            int network = model.networkPlayers();
            int shard = model.players();
            if (network > 0) return String.format(Locale.ROOT, "%,d on the network, %,d here", network, shard);
            return shard > 0 ? String.format(Locale.ROOT, "%,d here", shard) : "unknown";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String rivals() {
        try {
            RivalIntel intel = DoughBayClient.rivalIntel();
            if (intel == null) return "not read yet";
            String status = intel.shortStatus();
            return status == null || status.isBlank() ? "quiet" : status;
        } catch (Throwable t) {
            return "not read yet";
        }
    }

    private static String money(long value) {
        if (value >= 1_000_000) return String.format(Locale.ROOT, "$%.2fm", value / 1_000_000.0);
        if (value >= 1_000) return String.format(Locale.ROOT, "$%.1fk", value / 1_000.0);
        return "$" + value;
    }

    /** Discord takes a JSON body; only the content field is needed. */
    private static String json(String content) {
        StringBuilder escaped = new StringBuilder(content.length() + 16);
        for (char c : content.toCharArray()) {
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> { }
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) escaped.append(String.format("\\u%04x", (int) c));
                    else escaped.append(c);
                }
            }
        }
        return "{\"content\":\"" + escaped + "\"}";
    }
}

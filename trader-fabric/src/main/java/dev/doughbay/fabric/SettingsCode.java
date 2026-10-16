package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A whole tuning setup packed into one shareable code, and back again.
 *
 * <p>The values are written to compact JSON, gzipped, and spelled in Base32 -
 * the digits and capitals a person can read aloud or type without ambiguity,
 * grouped in fives behind a {@code DB1-} tag so a pasted code is recognisable
 * and a truncated one is caught. It carries only tuning: never the API key
 * (which lives in a separate file the code never reads) and never the webhook
 * URL, which is a secret the sender would not mean to hand over.
 *
 * <p>Nor does it carry what belongs to the account rather than to the setup:
 * how many auction and order slots a player's rank gives them. A friend with
 * 18 slots who loads a code made on 90 keeps his 18.
 *
 * <p>Importing applies every value the code carries and ignores any key this
 * build does not know, so a code from a newer or older version still loads what
 * it can rather than failing whole.
 */
public final class SettingsCode {
    private static final int VERSION = 1;
    private static final String PREFIX = "DB1";
    /** Never leaves this machine in a shared code. */
    private static final Set<String> NEVER_EXPORT = Set.of("webhook.url");
    /**
     * The account's own, set by its rank on the server: never written into a
     * code, and left alone when a code from an older build still carries them.
     */
    static final Set<String> PER_ACCOUNT = Set.of("slots.max", "orders.bid_slots", "session.wind_down");

    /**
     * Whether a setting stays with the player who set it. The slot counts,
     * and everything about following other players: who an account follows,
     * how many, and whether it does so at all is that account's own business
     * on the server, with a limit of its own there, and a friend who pastes a
     * code to copy a trading setup has not asked for his account to start
     * sending follow commands.
     */
    static boolean staysBehind(String key) {
        return key != null && (PER_ACCOUNT.contains(key) || key.startsWith("follow.") || key.equals("reports.auto_critical"));
    }
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private SettingsCode() {
    }

    /**
     * The result of importing a code: how many values applied, or why it failed.
     *
     * @param kept values the code carried that are the account's own and were left as they are
     */
    public record Result(boolean ok, int applied, int skipped, int kept, String error) {
        public static Result failed(String why) {
            return new Result(false, 0, 0, 0, why);
        }
    }

    /** Packs the current tuning into a shareable code. */
    public static String export() {
        return exportPreset(Map.of(), Map.of(), false);
    }

    /** A complete shareable snapshot, including empty item lists for reliable rollback. */
    static String exportPreset(Map<String, Double> changes, Map<String, String> lists) {
        return exportPreset(changes, lists, true);
    }

    private static String exportPreset(Map<String, Double> changes, Map<String, String> lists, boolean emptyLists) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("v", VERSION);
        ObjectNode numbers = root.putObject("n");
        for (Tuning.Setting s : Tuning.SETTINGS) {
            if (staysBehind(s.key())) continue;
            numbers.put(s.key(), s.clamp(changes.getOrDefault(s.key(), Tuning.get(s.key()))));
        }
        ObjectNode texts = root.putObject("t");
        for (Tuning.ListSetting l : Tuning.LISTS) {
            if (NEVER_EXPORT.contains(l.key())) continue;
            String value = lists.getOrDefault(l.key(), Tuning.text(l.key()));
            if (emptyLists || !value.isBlank()) texts.put(l.key(), value);
        }
        try {
            return encode(mapper.writeValueAsBytes(root));
        } catch (Exception e) {
            return "";
        }
    }


    /** Explicit privacy allowlist. Never export list/text settings or arbitrary config fields. */
    static final Set<String> REPORT_KEYS = Set.of(
            "buy.scale_with_bank",
            "buy.purchase_cap_pct",
            "buy.outstanding_cap_pct",
            "demand.clock",
            "demand.dead_pct",
            "buy.rank_by_value",
            "buy.max_open_per_market",
            "buy.reliability_rank",
            "buy.reliability_top_n",
            "buy.internal_pause_resume_sec",
            "buy.receipt_window_min",
            "watch.tracked_managed",
            "watch.pages",
            "watch.session_max_sec",
            "pace.recover_click_spam",
            "pace.human_steps",
            "pace.fast",
            "pace.step_min_sec",
            "pace.step_max_sec",
            "pace.command_min_sec",
            "pace.command_max_sec",
            "pace.gap_min_sec",
            "pace.gap_max_sec",
            "pace.long_gap_min_sec",
            "pace.long_gap_max_sec",
            "pace.long_gap_one_in",
            "tier.mid_from_pct",
            "tier.large_from_pct",
            "multi.enabled",
            "multi.scan_only",
            "orders.bid_min_room",
            "multi.payroll_here",
            "webhook.hive_one",
            "consign.enabled",
            "consign.share_pct",
            "consign.range",
            "consign.abandon_min",
            "consign.min_value",
            "hive.lending",
            "api.own_key",
            "hive.split_markets",
            "hive.split_seed",
            "hive.share_deals",
            "hive.tip_ttl_sec",
            "hive.reserve",
            "hive.max_loan",
            "hive.profit_share_pct",
            "multi.scan_share_pct",
            "slots.max",
            "list.singles",
            "list.singles_max",
            "list.singles_min_each",
            "list.singles_batch_profit",
            "feed.keyless",
            "keyless.sweep",
            "keyless.sweep_gap_sec",
            "keyless.sweep_every_min",
            "jackpot.min",
            "slots.quick_reserve_pct",
            "slots.quick_hold_min",
            "tier.mid_share_pct",
            "tier.large_share_pct",
            "tier.spill_min_roi_pct",
            "tier.spill_min_confidence_pct",
            "tier.large_min_sales_per_hour",
            "tier.large_max_open_per_market",
            "tier.large_stop_loss_min",
            "reprice.small_after_min",
            "reprice.mid_after_min",
            "reprice.large_after_min",
            "reprice.small_step_pct",
            "reprice.mid_step_pct",
            "reprice.large_step_pct",
            "reprice.minimum_roi_pct",
            "reprice.stop_loss_min",
            "reprice.below_cost_min",
            "reprice.below_cost_floor_pct",
            "reprice.to_market",
            "reprice.retry_min",
            "rival.crowded_ceiling_cut_pct",
            "rival.proven_min_flips",
            "rival.quiet_extra_small_slots",
            "rival.quiet_pace_factor",
            "rival.mirror_when_slow",
            "rival.mirror_slow_pct",
            "rival.min_sales",
            "rival.min_items",
            "rival.bot_score_threshold",
            "hud.panel",
            "hud.over_screens",
            "hud.corner",
            "hud.clock",
            "quiet.hide_containers",
            "quiet.hide_hotbar",
            "quiet.hide_chat",
            "quiet.vitals_when_low",
            "quiet.vitals_pct",
            "hud.flights",
            "hud.reduced_motion",
            "hud.mascot",
            "quiet.hide_crosshair",
            "slots.collect_unsold",
            "slots.adopt_orphans",
            "alerts.opportunities",
            "privacy.hide_name",
            "camera.orbit",
            "camera.distance",
            "hud.xp_bars",
            "hud.grid",
            "intel.enabled",
            "webhook.enabled",
            "webhook.alerts",
            "webhook.alert_death",
            "webhook.alert_evasion",
            "webhook.alert_paused",
            "webhook.alert_sale",
            "webhook.image",
            "webhook.every_min",
            "intel.hide_allies",
            "intel.range_blocks",
            "intel.max_players",
            "intel.lookup_gap_sec",
            "hud.rivals",
            "hud.status_line",
            "hud.sale_line",
            "hud.alerts",
            "hud.keys",
            "hud.keys_x",
            "hud.keys_y",
            "hud.alerts_left",
            "perf.no_afk_throttle",
            "market.adaptive",
            "market.low_value_max",
            "market.switch_min",
            "prune.enabled",
            "prune.min_loss",
            "prune.min_trades",
            "prune.lookback_days",
            "demand.time_of_day",
            "recovery.auto_reset_min",
            "evasion.enabled",
            "evasion.radius_blocks",
            "evasion.clear_before_resume_sec",
            "underdog.enabled",
            "underdog.new_markets_only",
            "underdog.order_slots",
            "underdog.from_follows",
            "underdog.undercut_pct",
            "underdog.min_rival_margin_pct",
            "underdog.min_flips",
            "underdog.max_targets",
            "orders.enabled",
            "orders.scan_min",
            "orders.collect_whole_stacks",
            "orders.one_trip",
            "orders.collect",
            "orders.deliver",
            "orders.source",
            "orders.max_targets",
            "evasion.warn_blocks",
            "evasion.player_idle_sec",
            "evasion.air",
            "evasion.wither",
            "evasion.on_damage",
            "evasion.rtp_on_damage",
            "liveness.enabled",
            "reports.auto_critical",
            "liveness.quiet_min",
            "liveness.alert",
            "liveness.self_heal",
            "join.auto",
            "update.check",
            "update.auto",
            "follow.enabled",
            "follow.auto",
            "follow.money",
            "follow.max",
            "follow.gap_sec",
            "join.delay_sec",
            "join.retry_sec",
            "session.auto_respawn_sec",
            "evasion.escape",
            "evasion.disconnect_blocks",
            "evasion.line_of_sight",
            "evasion.ranged_blocks",
            "evasion.projectile_blocks",
            "evasion.use_homes",
            "evasion.mob_blocks",
            "evasion.contact_sec",
            "evasion.glide_blocks",
            "recovery.give_up_after_min",
            "shelf.after_min",
            "shelf.min_gap_sec",
            "shelf.place_in_chest",
            "shelf.instead_of_loss",
            "stash.pocket_slot",
            "stash.hotbar_slot",
            "stash.restock_below",
            "pace.react_min_sec",
            "pace.react_max_sec",
            "stash.enabled",
            "pace.click_gap_min_sec",
            "pace.click_gap_max_sec",
            "safety.teleports_work",
            "evasion.wander_every_min",
            "evasion.rtp_wait_sec",
            "evasion.rtp_if_underwater",
            "evasion.rtp_abort_blocks",
            "evasion.reconnect_sec",
            "hud.only_when_live",
            "warmup.enabled",
            "warmup.markets",
            "warmup.samples",
            "quiet.enabled",
            "quiet.share_pct",
            "quiet.relax_pct",
            "quiet.bid_boost",
            "demand.population",
            "panel.hold_idle_sec",
            "session.auto_resume",
            "session.resume_after_leave",
            "restart.hop",
            "session.wind_down",
            "prices.only",
            "orders.bid",
            "orders.bid_slots",
            "orders.depth_share_pct",
            "orders.bid_stacks",
            "upload.enabled",
            "orders.bid_min_confidence",
            "orders.bid_min_samples",
            "orders.bid_min_sales_per_hour",
            "inventory.sweep_enabled",
            "inventory.sweep_min",
            "orders.bid_min_margin_pct",
            "orders.bid_on_real_trades",
            "orders.chase",
            "orders.chase_min_margin_pct",
            "orders.size_by_sale",
            "orders.nofill_min",
            "orders.bid_min_value",
            "orders.bid_max_value",
            "orders.bid_max_hours",
            "orders.full_sweep_min",
            "orders.pages",
            "boxes.enabled",
            "boxes.max_open",
            "boxes.min_value",
            "boxes.sweep_min",
            "boxes.sweep_pages",
            "boxes.min_confidence_pct");

    public static String exportForReport() {
        var mapper = new ObjectMapper();
        var root = mapper.createObjectNode(); root.put("v", 1);
        var numbers = root.putObject("n"); root.putObject("t");
        for (String key : new java.util.TreeSet<>(REPORT_KEYS)) {
            double value = Tuning.get(key);
            if (Double.isFinite(value)) numbers.put(key, value);
        }
        try { return encode(mapper.writeValueAsBytes(root)); }
        catch (java.io.IOException e) { throw new IllegalStateException("Could not prepare settings snapshot"); }
    }

    static void validateReportCode(String code) {
        if (code == null || code.isEmpty()) return;
        if (code.length() > 12000 || !code.matches("DB1-[A-Z2-7]+(?:-[A-Z2-7]+)*"))
            throw new IllegalArgumentException("Invalid report settings code");
        try (var gz = new GZIPInputStream(new ByteArrayInputStream(base32Decode(code)))) {
            byte[] bytes = gz.readNBytes(65537);
            if (bytes.length > 65536) throw new IllegalArgumentException();
            var root = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
            if (!encode(bytes).equals(code)) throw new IllegalArgumentException();
            if (root == null || !root.isObject() || root.size() != 3 || !root.path("v").isIntegralNumber()
                    || root.path("v").asInt() != 1 || !root.path("n").isObject()
                    || !root.path("t").isObject() || root.path("t").size() != 0) throw new IllegalArgumentException();
            var fields = root.path("n").fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!REPORT_KEYS.contains(field.getKey()) || !field.getValue().isNumber()
                        || !Double.isFinite(field.getValue().asDouble())) throw new IllegalArgumentException();
            }
        } catch (Exception e) { throw new IllegalArgumentException("Invalid or unsafe report settings code"); }
    }

    /** The code for a settings document. */
    static String encode(byte[] json) throws java.io.IOException {
        return PREFIX + "-" + group(base32Encode(gzip(json)));
    }

    /** Applies a code to the live tuning; returns what it did or why it could not. */
    public static Result apply(String code) {
        if (code == null || code.isBlank()) return Result.failed("empty code");
        byte[] gz;
        try {
            gz = base32Decode(code);
        } catch (RuntimeException e) {
            return Result.failed("not a valid code");
        }
        byte[] json;
        try {
            json = gunzip(gz);
        } catch (Exception e) {
            return Result.failed("code is corrupt or truncated");
        }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(json);
        } catch (Exception e) {
            return Result.failed("code is corrupt");
        }
        if (root == null || !root.hasNonNull("v")) return Result.failed("not a settings code");

        int applied = 0;
        int skipped = 0;
        int kept = 0;
        JsonNode numbers = root.get("n");
        if (numbers != null && numbers.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = numbers.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (Tuning.setting(e.getKey()) == null) {
                    skipped++;
                    continue;
                }
                if (staysBehind(e.getKey())) {
                    kept++;
                    continue;
                }
                Tuning.set(e.getKey(), e.getValue().asDouble());
                applied++;
            }
        }
        JsonNode texts = root.get("t");
        if (texts != null && texts.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = texts.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey();
                if (NEVER_EXPORT.contains(key) || !isKnownList(key)) {
                    skipped++;
                    continue;
                }
                Tuning.setText(key, e.getValue().asText(""));
                applied++;
            }
        }
        if (applied == 0) return Result.failed("code carried nothing this version understands");
        return new Result(true, applied, skipped, kept, "");
    }

    private static boolean isKnownList(String key) {
        for (Tuning.ListSetting l : Tuning.LISTS) if (l.key().equals(key)) return true;
        return false;
    }

    // ---- transport ----------------------------------------------------------

    private static byte[] gzip(byte[] data) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] data) throws java.io.IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return gz.readAllBytes();
        }
    }

    /** Groups the code in fives with dashes, so it reads like a key rather than a wall. */
    private static String group(String body) {
        StringBuilder out = new StringBuilder(body.length() + body.length() / 5);
        for (int i = 0; i < body.length(); i++) {
            if (i > 0 && i % 5 == 0) out.append('-');
            out.append(body.charAt(i));
        }
        return out.toString();
    }

    private static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(ALPHABET.charAt((buffer >> bits) & 0x1F));
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return out.toString();
    }

    private static byte[] base32Decode(String code) {
        String clean = code.trim().toUpperCase(java.util.Locale.ROOT);
        if (clean.startsWith(PREFIX)) clean = clean.substring(PREFIX.length());
        StringBuilder symbols = new StringBuilder();
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (ALPHABET.indexOf(c) >= 0) symbols.append(c);
        }
        if (symbols.length() == 0) throw new IllegalArgumentException("no code");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < symbols.length(); i++) {
            buffer = (buffer << 5) | ALPHABET.indexOf(symbols.charAt(i));
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}

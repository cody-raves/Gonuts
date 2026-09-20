package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live-tunable numbers behind the trader: caps, tier sizes, pull-back
 * timing, rival influence, pacing, evasion. Every setting is declared once
 * here with its bounds and default; the Settings tab draws itself from this
 * list, values apply on the next tick, and changes are saved to
 * {@code tuning.json} in the config folder.
 *
 * <p>The defaults are exactly the values the code shipped with, so a fresh
 * install behaves as before and a tuned one keeps its tuning.
 */
public final class Tuning {
    public enum Kind { PERCENT, COUNT, MINUTES, SECONDS, FACTOR, RATE, BLOCKS, TOGGLE }

    public record Setting(String key, String group, String label, String description,
                          double defaultValue, double min, double max, Kind kind) {
        public String format(double value) {
            return switch (kind) {
                case PERCENT -> trim(value) + "%";
                case MINUTES -> trim(value) + " min";
                case SECONDS -> trim(value) + " s";
                case BLOCKS -> trim(value) + " blocks";
                case RATE -> trim(value) + "/h";
                case FACTOR -> "x" + trim(value);
                case TOGGLE -> value >= 0.5 ? "on" : "off";
                case COUNT -> trim(value);
            };
        }

        public double clamp(double value) {
            if (Double.isNaN(value)) return defaultValue;
            return Math.max(min, Math.min(max, value));
        }
    }

    private static String trim(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e9) return String.valueOf((long) value);
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    public static final List<Setting> SETTINGS = List.of(
            // Buying
            new Setting("buy.purchase_cap_pct", "Buying", "Per-purchase cap",
                    "Most one buy may cost, as a share of the bank", 14, 0.5, 50, Kind.PERCENT),
            new Setting("buy.outstanding_cap_pct", "Buying", "Money out at once",
                    "Most that may sit in listings, as a share of the bank", 70, 10, 95, Kind.PERCENT),
            new Setting("demand.clock", "Buying", "Time-of-day demand",
                    "Rank markets by how busy they are at this hour and skip their dead hours (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("demand.dead_pct", "Buying", "Dead hour means",
                    "Skip a market whose demand this hour is under this share of its own average", 50, 0, 90, Kind.PERCENT),
            new Setting("buy.rank_by_value", "Buying", "Spend turns on the biggest trades",
                    "Order the candidates that pass the gates by what they are expected to make, rather than by how "
                    + "good a deal they are. The bot can only work the auction house so many times an hour, so a turn "
                    + "spent on a small trade is a turn not spent on a large one (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("buy.max_open_per_market", "Buying", "Listings per market",
                    "How many of one item and stack size may be up at once", 8, 1, 10, Kind.COUNT),
            new Setting("buy.reliability_rank", "Buying", "Concentrate on the reliable markets",
                    "Rank markets by how reliably a whole stack clears at a profit - margin times how sure the sale "
                    + "is times sustained volume, steadier markets first - and work the top few to their per-market cap "
                    + "instead of ordering every market that passes the gates by deal size. Fills the book with proven "
                    + "movers. Off by default (1 on, 0 off)",
                    0, 0, 1, Kind.TOGGLE),
            new Setting("buy.reliability_top_n", "Buying", "How many markets in play",
                    "When the reliability ranking is on, how many top-ranked markets to keep buying into at once. Keep "
                    + "it comfortably above the quick-flip slot count so the fast lane never starves",
                    35, 1, 90, Kind.COUNT),
            new Setting("buy.internal_pause_resume_sec", "Buying", "Auto-resume after",
                    "Quiet time before a pause the bot raised itself is resumed", 10, 10, 900, Kind.SECONDS),
            new Setting("buy.receipt_window_min", "Buying", "Unlisted purchase window",
                    "How long a purchase receipt is kept to list a stack that never got listed", 15, 1, 120, Kind.MINUTES),
            // Pacing
            new Setting("watch.tracked_managed", "Pacing", "Watch list edited in game",
                    "Set to 1 the first time the watch list is edited from the Items tab. Once set, watch.tracked is "
                    + "the whole truth - an empty list means watch nothing extra - instead of falling back to the "
                    + "config file's tracked commodities. Leave at 0 to keep using the config set", 0, 0, 1, Kind.TOGGLE),
            new Setting("watch.pages", "Pacing", "Auction pages per look",
                    "How deep into the auction one look goes before closing the page. Page one is where a mispriced item "
                    + "first lands, but deeper pages hold ones nobody has taken. Costs no API requests at all",
                    4, 1, 10, Kind.COUNT),
            new Setting("pace.human_steps", "Pacing", "Human pauses between steps",
                    "Pause a varying moment between the steps of one action: after a page opens, before a row is clicked, "
                    + "with the stack in hand, before a price is typed. Slower, and far less machine-like (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("pace.step_min_sec", "Pacing", "Step pause, shortest",
                    "The shortest pause between two steps of the same action", 0.45, 0.1, 5, Kind.SECONDS),
            new Setting("pace.step_max_sec", "Pacing", "Step pause, longest",
                    "The longest pause between two steps of the same action", 1.3, 0.2, 10, Kind.SECONDS),
            new Setting("pace.command_min_sec", "Pacing", "Command gap, shortest",
                    "The shortest gap between two commands the mod sends", 0.9, 0.6, 10, Kind.SECONDS),
            new Setting("pace.command_max_sec", "Pacing", "Command gap, longest",
                    "The longest gap between two commands the mod sends", 1.8, 0.6, 15, Kind.SECONDS),
            new Setting("pace.gap_min_sec", "Pacing", "Watch gap, shortest",
                    "Shortest pause between looks at Recently Listed", 3, 0.5, 30, Kind.SECONDS),
            new Setting("pace.gap_max_sec", "Pacing", "Watch gap, longest",
                    "Longest normal pause between looks", 8, 1, 60, Kind.SECONDS),
            new Setting("pace.long_gap_min_sec", "Pacing", "Long gap, shortest",
                    "Occasional longer pause, lower end", 15, 1, 120, Kind.SECONDS),
            new Setting("pace.long_gap_max_sec", "Pacing", "Long gap, longest",
                    "Occasional longer pause, upper end", 40, 1, 300, Kind.SECONDS),
            new Setting("pace.long_gap_one_in", "Pacing", "Long gap frequency",
                    "One look in this many takes the long gap; 0 never", 8, 0, 50, Kind.COUNT),
            // Tiers
            new Setting("tier.mid_from_pct", "Tiers", "Mid tier starts at",
                    "A buy this share of the bank or more is a mid ticket", 0.35, 0.1, 20, Kind.PERCENT),
            new Setting("tier.large_from_pct", "Tiers", "Large tier starts at",
                    "A buy this share of the bank or more is a large ticket", 1.4, 0.5, 50, Kind.PERCENT),
            new Setting("multi.enabled", "Multi client", "Shared API key",
                    "Several accounts run DoughBay against one API key and one ledger. The key's allowance is then divided "
                    + "between the clients that are running, and each client says whether it trades or only scans (1 on, 0 off)",
                    0, 0, 1, Kind.TOGGLE),
            new Setting("multi.scan_only", "Multi client", "This client only scans",
                    "With a shared key: this client reads the market, the order house and the pages into the shared ledger, "
                    + "and never trades. No buying, listing, repricing, orders or payroll. Only meaningful with the shared key on",
                    0, 0, 1, Kind.TOGGLE),
            new Setting("orders.bid_min_room", "Orders", "Ask the hive below this room",
                    "With less than this left to spend, the bid desk asks another client of the hive for a loan rather "
                    + "than stopping. Only used when lending is on",
                    500000, 0, 20000000, Kind.COUNT),
            new Setting("multi.payroll_here", "Multi client", "Pay payroll from this client",
                    "Payroll rules and their profit window are shared, so two clients paying from them race each other "
                    + "and pay some profit twice. Exactly one client in the hive should have this on (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("webhook.hive_one", "Multi client", "One hourly webhook for the hive",
                    "When several bots share this ledger the hourly report already reads the whole hive, so each bot "
                    + "posting sends the same note two or three times over. On, only the elected host posts it (the same "
                    + "bot that runs the Discord embed), so the channel gets one consolidated report. Event alerts still "
                    + "come from whichever bot they happened to (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("consign.enabled", "Consignment", "Take boxes from friends",
                    "A friend on the ally list stands beside the bot holding a filled box; a moment later they are not "
                    + "holding it and the bot is. The box is sold and they are paid a share of what it made. Strangers "
                    + "are ignored: the payout goes out on its own and a wrong guess is real money (1 on, 0 off)",
                    0, 0, 1, Kind.TOGGLE),
            new Setting("consign.share_pct", "Consignment", "Their share of the profit",
                    "What the friend is paid out of what their box actually made, once it sells", 50, 0, 100, Kind.PERCENT),
            new Setting("consign.range", "Consignment", "Hand-over range",
                    "How close a friend must be for a box arriving to be counted as theirs", 6, 2, 24, Kind.COUNT),
            new Setting("consign.abandon_min", "Consignment", "Forget an unlisted box after",
                    "Minutes a handed-over box may take to reach the auction before the claim is dropped. Until it is "
                    + "listed nothing is owed, so a box that is lost or taken back costs nothing",
                    60, 5, 1440, Kind.MINUTES),
            new Setting("consign.min_value", "Consignment", "Smallest box worth taking",
                    "Contents worth less than this are treated as ours, not as a consignment", 50000, 0, 10000000, Kind.COUNT),
            new Setting("hive.lending", "Multi client", "Lend between accounts",
                    "A client short of cash may ask another for a loan, and repay it with a share of what the trade made. "
                    + "Money only ever moves between your own accounts (1 on, 0 off)",
                    0, 0, 1, Kind.TOGGLE),
            new Setting("api.own_key", "Multi client", "This client has its own key",
                    "When each client of a hive holds a separate token, none needs to split the request budget with "
                    + "the others; each keeps the whole allowance (1 own key, 0 shared key)", 0, 0, 1, Kind.TOGGLE),
            new Setting("hive.share_deals", "Multi client", "Hand deals to the hive",
                    "When a client is full or short of cash and finds a good buy it cannot take, it posts the deal "
                    + "for a sibling with a free slot to grab. Only ever offers what it will not take itself, so the "
                    + "two never chase the same deal (1 on, 0 off)", 0, 0, 1, Kind.TOGGLE),
            new Setting("hive.tip_ttl_sec", "Multi client", "How long a tip lives",
                    "A handed-over deal is dropped after this many seconds, because auction listings go stale fast",
                    90, 15, 600, Kind.SECONDS),
            new Setting("hive.reserve", "Multi client", "Never lend below",
                    "A lender keeps at least this much for its own trading", 5000000, 0, 100000000, Kind.COUNT),
            new Setting("hive.max_loan", "Multi client", "Biggest single loan",
                    "The most one client will ask for at a time", 2000000, 0, 50000000, Kind.COUNT),
            new Setting("hive.profit_share_pct", "Multi client", "Lender's share of the profit",
                    "Repaid on top of the principal, out of what the funded trade actually made",
                    50, 0, 100, Kind.PERCENT),
            new Setting("multi.scan_share_pct", "Multi client", "A scanner's share of the key",
                    "How much of its equal share a scanning client actually takes, leaving the rest to the trading client. "
                    + "A scanner's real value is the pages it walks, which cost no API requests at all",
                    50, 10, 100, Kind.PERCENT),
            new Setting("slots.max", "Tiers", "Auction slots",
                    "Listings your rank allows at once: 18 with no rank, 45 with Donut+, 90 with Donut++ and up", 45, 1, 90, Kind.COUNT),
            new Setting("list.singles", "Selling", "Piece out premium markets",
                    "For the markets that measurably pay more per unit for a single than for a stack, break the bought "
                    + "stack into singles and list them one at a time at the single price instead of dumping the whole "
                    + "stack at the stack price. Only markets the sales history proves prefer singles are touched, and "
                    + "nothing ever lists below cost plus the minimum profit. Uses one auction slot per single, so it "
                    + "leans on spare slot capacity. Off by default (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("list.singles_max", "Selling", "Singles up per market at once",
                    "When piecing a stack out, how many singles of one item may be listed at a time. Once this many "
                    + "are up, the rest of the stack is listed whole instead of piecing further. Keeps one stack from "
                    + "flooding the book and from undercutting its own singles down a long ladder",
                    5, 1, 30, Kind.COUNT),
            new Setting("slots.quick_reserve_pct", "Tiers", "Quick-flip reserve",
                    "Share of the auction slots kept for quick flips; long holds may never fill these, so the fast churn always has room",
                    33, 0, 60, Kind.PERCENT),
            new Setting("slots.quick_hold_min", "Tiers", "Quick flip means",
                    "A trade expected to sell within this long counts as quick; anything slower, or any listing up longer than this, is a long hold",
                    60, 15, 360, Kind.MINUTES),
            new Setting("tier.mid_share_pct", "Tiers", "Mid pool size",
                    "Share of the auction slots reserved for mid tickets", 33, 0, 80, Kind.PERCENT),
            new Setting("tier.large_share_pct", "Tiers", "Large pool size",
                    "Share of the auction slots reserved for large tickets", 11, 0, 80, Kind.PERCENT),
            new Setting("tier.spill_min_roi_pct", "Tiers", "Spill-over ROI",
                    "A deal with at least this ROI may use another tier's slots", 60, 5, 500, Kind.PERCENT),
            new Setting("tier.spill_min_confidence_pct", "Tiers", "Spill-over confidence",
                    "Confidence needed for a spill-over buy", 60, 0, 100, Kind.PERCENT),
            new Setting("tier.large_min_sales_per_hour", "Tiers", "Large ticket demand gate",
                    "Completed sales per hour a market needs before a large buy", 0.4, 0, 50, Kind.RATE),
            new Setting("tier.large_max_open_per_market", "Tiers", "Large listings per market",
                    "How many large tickets of one item may be up at once", 2, 1, 10, Kind.COUNT),
            new Setting("tier.large_stop_loss_min", "Tiers", "Large stop-loss after",
                    "A large ticket unsold this long is let go at break-even", 120, 10, 1440, Kind.MINUTES),
            // Pull-backs
            new Setting("reprice.small_after_min", "Pull-backs", "Small: reprice after",
                    "Minutes a small listing sits before it is pulled back and relisted lower", 8, 1, 720, Kind.MINUTES),
            new Setting("reprice.mid_after_min", "Pull-backs", "Mid: reprice after",
                    "Minutes a mid listing sits before a pull-back", 15, 1, 720, Kind.MINUTES),
            new Setting("reprice.large_after_min", "Pull-backs", "Large: reprice after",
                    "Minutes a large listing sits before a pull-back", 25, 1, 720, Kind.MINUTES),
            new Setting("reprice.small_step_pct", "Pull-backs", "Small: price step",
                    "How far each pull-back lowers a small listing", 10, 1, 50, Kind.PERCENT),
            new Setting("reprice.mid_step_pct", "Pull-backs", "Mid: price step",
                    "How far each pull-back lowers a mid listing", 7, 1, 50, Kind.PERCENT),
            new Setting("reprice.large_step_pct", "Pull-backs", "Large: price step",
                    "How far each pull-back lowers a large listing", 5, 1, 50, Kind.PERCENT),
            new Setting("reprice.stop_loss_min", "Pull-backs", "Stop asking for profit after",
                    "Minutes a listing may sit before the pull-backs stop insisting on the minimum profit and will "
                    + "come down to what it cost. A held slot earns nothing; 0 never gives up the margin",
                    240, 0, 2880, Kind.MINUTES),
            new Setting("reprice.below_cost_min", "Pull-backs", "Take a loss after",
                    "Minutes a listing may sit before it may be sold under what it cost, freeing the slot. A stack "
                    + "that will not sell at cost today will not sell at cost tomorrow. 0 never sells at a loss",
                    720, 0, 10080, Kind.MINUTES),
            new Setting("reprice.below_cost_floor_pct", "Pull-backs", "Loss floor",
                    "The least it may be sold for once losses are allowed, as a share of what it cost",
                    80, 10, 100, Kind.PERCENT),
            new Setting("reprice.to_market", "Pull-backs", "Price against the market",
                    "Let the market set the next ask instead of the clock. Off, each pull-back takes a fixed step "
                    + "down from our own last ask, whatever the market is doing. On, a stack already at or under "
                    + "the market for its own stack size is left alone - the price is not why it is sitting - and "
                    + "one that is above comes down to the market in a single move rather than a rung at a time. "
                    + "The floor is unchanged either way (1 on, 0 off)",
                    0, 0, 1, Kind.TOGGLE),
            new Setting("reprice.retry_min", "Pull-backs", "Retry a failed pull-back after",
                    "Wait before trying the same listing again", 3, 1, 60, Kind.MINUTES),
            // Rivals
            new Setting("rival.crowded_ceiling_cut_pct", "Rivals", "Crowded market ceiling cut",
                    "Lower the buy ceiling this much where a rival is active right now", 5, 0, 50, Kind.PERCENT),
            new Setting("rival.proven_min_flips", "Rivals", "Proven market threshold",
                    "Rival flips in a day that earn a market one extra listing", 3, 1, 50, Kind.COUNT),
            new Setting("rival.quiet_extra_small_slots", "Rivals", "Quiet field: extra small slots",
                    "Small pool grows by this many when no rival is active", 15, 0, 20, Kind.COUNT),
            new Setting("rival.quiet_pace_factor", "Rivals", "Quiet field: pace",
                    "Watch gaps are multiplied by this when no rival is active", 0.6, 0.3, 1, Kind.FACTOR),
            new Setting("rival.mirror_when_slow", "Rivals", "Mirror rivals when slow",
                    "When rivals are on and our sales have gone quiet, watch the markets they are working right now (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("rival.mirror_slow_pct", "Rivals", "Slow means",
                    "Sales in the last 30 min below this share of our 6-hour average count as slow", 40, 10, 90, Kind.PERCENT),
            new Setting("rival.min_sales", "Rivals", "Rival: minimum sales/day",
                    "A seller needs this many sales in a day to count as a rival", 40, 5, 1000, Kind.COUNT),
            new Setting("rival.min_items", "Rivals", "Rival: minimum items",
                    "Different items a seller needs in a day to count as a rival", 6, 1, 50, Kind.COUNT),
            new Setting("rival.bot_score_threshold", "Rivals", "Bot score threshold",
                    "Sellers scoring this or higher count as auto-traders", 60, 10, 100, Kind.PERCENT),
            // Evasion
            new Setting("hud.panel", "HUD", "Status panel",
                    "The always-on panel: state, slots, money out, what is happening (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("hud.over_screens", "HUD", "Show under screens",
                    "Keep the panel visible while the auction house or inventory is open (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("hud.corner", "HUD", "Panel corner",
                    "0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right", 0, 0, 3, Kind.COUNT),
            new Setting("hud.clock", "HUD", "Clock",
                    "Show the local time on the panel's title line (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("quiet.hide_containers", "HUD", "Hide the bot's screens",
                    "Do not draw the auction and order pages the bot opens for itself. It clicks slots by number and "
                    + "never looks at the screen, so nothing is drawn and nothing changes for it - the strobing just "
                    + "stops. Your own inventory is untouched (1 on, 0 off)",
                    0, 0, 1, Kind.TOGGLE),
            new Setting("quiet.hide_hotbar", "HUD", "Hide the hotbar",
                    "Hide the vanilla hotbar and experience bar; DoughBay's own panel still draws (1 on, 0 off)",
                    0, 0, 1, Kind.TOGGLE),
            new Setting("quiet.hide_chat", "HUD", "Hide the chat",
                    "Stop drawing the chat box. The bot reads chat off the network rather than off the screen, so "
                    + "sale receipts still arrive and are still parsed with nothing drawn (1 on, 0 off)",
                    0, 0, 1, Kind.TOGGLE),
            new Setting("quiet.vitals_when_low", "HUD", "Health and hunger only when low",
                    "Show the health and hunger bars only once one of them drops to the mark below (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("quiet.vitals_pct", "HUD", "Low means",
                    "The share of full health or hunger at or below which the bars come back", 50, 5, 100, Kind.PERCENT),
            new Setting("hud.flights", "HUD", "Item flights",
                    "Animate an item into its slot when listed and out to the right when sold (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("hud.reduced_motion", "HUD", "Reduced motion",
                    "Keep the mascot still and remove HUD movement while preserving status changes", 0, 0, 1, Kind.TOGGLE),
            new Setting("hud.mascot", "HUD", "Mascot",
                    "Show the mascot on the panel (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("quiet.hide_crosshair", "HUD", "Hide the crosshair",
                    "The bot clicks slots by number and never aims at anything, so the crosshair is a dot pinned "
                    + "over a view it has no part in (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("slots.collect_unsold", "Slots", "Collect expired listings",
                    "A listing that timed out unsold sits on your auction page saying \"click to collect\" and holds "
                    + "a slot the whole time, without being for sale. Collect it during the slot check: the stock "
                    + "comes back and is listed again at today's price (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("slots.adopt_orphans", "Slots", "Adopt listings the ledger lost",
                    "A listing on your own auction page that no position claims, seen twice running, is taken back "
                    + "under management so it can be repriced and pulled back. What it cost is unknown, so it is "
                    + "booked at break-even and no profit is invented (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("alerts.opportunities", "HUD", "Announce opportunities",
                    "Pop an alert for each new opportunity the scanner finds. Written for a person deciding by "
                    + "hand; the bot now reads the same snapshot and acts on it within the second, so these "
                    + "mostly push the sales, listings and fills off the screen (1 on, 0 off)",
                    0, 0, 1, Kind.TOGGLE),
            new Setting("camera.orbit", "HUD", "Drift the camera",
                    "Swing the view slowly around the player while the bot trades. It never uses the camera - slots "
                    + "are clicked by number - so the view is spare. Any turn of the mouse hands it straight back "
                    + "(1 on, 0 off)",
                    0, 0, 1, Kind.TOGGLE),
            new Setting("camera.distance", "HUD", "Camera distance",
                    "How far back the drifting camera sits, before anything solid gets in the way", 6, 2, 12, Kind.COUNT),
            new Setting("hud.xp_bars", "HUD", "Slots as experience bars",
                    "Draw the auction slots as stacked experience bars rather than a grid of cells. The bar is "
                    + "already notched into eighteen and the top rank grants ninety slots, so five of them is the "
                    + "whole book exactly - and it is a shape the eye already knows (1 on, 0 off)",
                    0, 0, 1, Kind.TOGGLE),
            new Setting("hud.grid", "HUD", "Slot grid",
                    "Show the auction-slot grid on the panel (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("intel.enabled", "HUD", "Who is nearby",
                    "A panel showing the players near you: their gear, its wear and enchantments, and what the server's "
                    + "profile says about them, kills against deaths (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("webhook.enabled", "HUD", "Hourly status webhook",
                    "Post a short report to the webhook URL below, on the interval set here (1 on, 0 off)",
                    0, 0, 1, Kind.TOGGLE),
            new Setting("webhook.alerts", "HUD", "Send alerts at all",
                    "The master switch for the immediate messages below. Off means only the hourly report is posted",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("webhook.alert_death", "HUD", "Alert on deaths",
                    "Post when the player dies, naming what was lost and what it cost", 1, 0, 1, Kind.TOGGLE),
            new Setting("webhook.alert_evasion", "HUD", "Alert on escapes",
                    "Post when the session pauses or teleports away, with the reason. A player who caused it is shown "
                    + "with their face and what they were carrying",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("webhook.alert_paused", "HUD", "Alert when it stalls",
                    "Post when the session pauses for anything else and stays paused, so a stuck bot is not silent",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("webhook.alert_sale", "HUD", "Alert on big sales",
                    "Post when a single sale clears the profit below. 0 never posts", 0, 0, 50000000, Kind.COUNT),
            new Setting("webhook.image", "HUD", "Report as a picture",
                    "Draw the report and post it as an image rather than text; falls back to text if drawing fails "
                    + "(1 picture, 0 text)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("webhook.every_min", "HUD", "Report every",
                    "How long between reports", 60, 5, 720, Kind.MINUTES),
            new Setting("intel.hide_allies", "HUD", "Strangers only",
                    "Leave the people on your ally list out of the panel: they are known to be harmless and asking the "
                    + "server about them wastes a request (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("intel.range_blocks", "HUD", "Show players within",
                    "How far out the panel looks", 120, 8, 256, Kind.BLOCKS),
            new Setting("intel.max_players", "HUD", "Players shown",
                    "The nearest this many, closest first", 3, 1, 8, Kind.COUNT),
            new Setting("intel.lookup_gap_sec", "HUD", "Profile lookup gap",
                    "The least time between two profile requests, so curiosity never slows the market feed",
                    6, 1, 120, Kind.SECONDS),
            new Setting("hud.rivals", "HUD", "Rival status",
                    "Show the rival count on the slots line (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("hud.status_line", "HUD", "Status line",
                    "Show the one-line 'what is happening now' (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("hud.sale_line", "HUD", "Sale line",
                    "Flash the last sale on the status line (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("hud.alerts", "HUD", "Alerts",
                    "Pop-up alerts for sales and events (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("hud.keys", "HUD", "Key legend",
                    "A small legend showing what each GoNuts key does, using your current bindings, so nobody has to "
                    + "memorise them (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("hud.keys_x", "HUD", "Key legend across",
                    "Where the key legend sits left-to-right, as a percent of the screen: 0 is the left edge, 100 the "
                    + "right", 0, 0, 100, Kind.PERCENT),
            new Setting("hud.keys_y", "HUD", "Key legend down",
                    "Where the key legend sits top-to-bottom, as a percent of the screen: 0 is the top, 100 the bottom",
                    100, 0, 100, Kind.PERCENT),
            new Setting("hud.alerts_left", "HUD", "Alerts on the left",
                    "Alerts slide in on the left instead of the right (1 on, 0 off)", 0, 0, 1, Kind.TOGGLE),
            new Setting("perf.no_afk_throttle", "Performance", "Keep running when minimized",
                    "Minecraft caps an unfocused, minimized, or idle window to 10 fps, which starves the bot's loop - a "
                    + "problem you cannot avoid running several clients at once, since all but one must be minimized, and a "
                    + "bot never touches the keyboard so it counts as idle anyway. On, the client keeps ticking at full rate "
                    + "no matter its window state. Turn off to restore vanilla power-saving (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("market.adaptive", "Adaptive market", "Adapt settings to the market",
                    "Watches what is actually selling and, when the market's character changes, applies a matching preset "
                    + "on top of your settings - dropping the value/margin floors when the market floods with cheap items "
                    + "so it stops missing them, easing off when it goes slow - and puts your settings back when it "
                    + "returns to normal. Every switch is logged. Off leaves your settings fixed (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("market.low_value_max", "Adaptive market", "Low-value market when median sale under",
                    "The market counts as flooded with cheap items when the median of what sold in the last 90 minutes is "
                    + "below this. Then the low-value preset lowers the value/margin floors so small stock is not skipped",
                    15000, 0, 500000, Kind.COUNT),
            new Setting("market.switch_min", "Adaptive market", "Hold a regime this long before switching",
                    "A new market regime has to persist this many minutes before its preset is applied, so the settings do "
                    + "not flip back and forth on a brief lull or spike",
                    5, 1, 60, Kind.MINUTES),
            new Setting("prune.enabled", "Adaptive market", "Drop markets that keep losing money",
                    "Selection ranks markets on price confidence and how fast they fill - what a snapshot can see - but not "
                    + "on whether they actually paid off. This feeds the ledger back in: a market whose settled trades add up "
                    + "to a real net loss is kept out of the auto-picked pool, and its slot goes to one that pays. A market "
                    + "you pinned or are still holding is never dropped. Off by default; it changes what gets bought (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("prune.min_loss", "Adaptive market", "Net loss before a market is dropped",
                    "How deep a market's total settled loss must be before it is pruned. A market sitting near break-even is "
                    + "kept; only a sustained drain past this is dropped",
                    50000, 0, 100000000, Kind.COUNT),
            new Setting("prune.min_trades", "Adaptive market", "Settled trades before a loss counts",
                    "A market must have settled at least this many trades before its loss is treated as a verdict rather than "
                    + "a bad run, so one unlucky flip never prunes a market",
                    10, 1, 500, Kind.COUNT),
            new Setting("prune.lookback_days", "Adaptive market", "Judge a market on the last",
                    "How many days of settled trades a market is judged on. A market is never banned for good: only its "
                    + "recent record counts, so an old loss ages out of this window and a market that has since turned "
                    + "around flows back into the pool on its own. A dropped market stops trading, its recent trades decay "
                    + "out of the window, and once too few remain it is no longer judged a loser and gets a fresh try",
                    5, 1, 90, Kind.COUNT),
            new Setting("demand.time_of_day", "Adaptive market", "Favour markets that fill at this hour",
                    "A day is not flat - a market can be dead at 4am and pour at 8pm - but selection ranks on a market's "
                    + "flat average, blind to that. With this on, each market's score is nudged up when the current hour is "
                    + "busier than its own daily average and down when it is quieter, so scan slots drift toward what is "
                    + "actually filling now. Clamped so it is a nudge, not a lever, and needs a market to have a real daily "
                    + "shape before it counts. Off by default; it changes which markets are scanned (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("recovery.auto_reset_min", "Recovery", "Auto force-reset a wedged session after",
                    "If a recovered position gets stuck past every automatic recovery - an exact-book scan that keeps "
                    + "being refused, a checkpoint that never lands - the session sits PAUSED and does nothing. After this "
                    + "many minutes wedged (well past any real recovery, which takes seconds), force-reset it: abandon the "
                    + "stuck exposure, clear the lock, and scan fresh. The held items stay in the inventory and re-list on "
                    + "the next sweep, so nothing is lost but the ledger's memory of them. 0 turns it off", 10, 0, 120, Kind.MINUTES),
            new Setting("evasion.enabled", "Evasion", "Evasion",
                    "Watch for players and get out of the way. Off means the session never pauses or teleports for "
                    + "anyone, whatever the rings below say (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("evasion.radius_blocks", "Evasion", "Evasion radius",
                    "Another player within this distance pauses the session", 128, 4, 128, Kind.BLOCKS),
            new Setting("evasion.clear_before_resume_sec", "Evasion", "Resume after clear",
                    "Seconds the area must stay clear before the session resumes", 60, 5, 900, Kind.SECONDS),
            // Underdog
            new Setting("underdog.enabled", "Underdog", "Underdog",
                    "Shadow the rivals' proven flips: buy where they buy, list a notch under their sale price (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("underdog.new_markets_only", "Underdog", "Only bid on new ground",
                    "On, the underdog bids only in markets this account has not sold in for a day, so it widens the "
                    + "book instead of deepening it. That excluded 94 markets and left it five to work. Off, it also "
                    + "bids in markets we already flip: the same item bought cheaper through the order house rather "
                    + "than off the auction page. Either way it never doubles up on an item and stack size that "
                    + "already has a bid out or stock held (1 on, 0 off)",
                    0, 0, 1, Kind.TOGGLE),
            new Setting("underdog.order_slots", "Underdog", "Order slots for new ground",
                    "Bid desk slots kept for markets a rival works profitably and we are absent from. Our own statistics "
                    + "can never justify a market we have never traded; the rival's completed flips are the evidence",
                    8, 0, 30, Kind.COUNT),
            new Setting("underdog.undercut_pct", "Underdog", "Undercut",
                    "List this far under the rival's completed sale price", 5, 0, 30, Kind.PERCENT),
            new Setting("underdog.min_rival_margin_pct", "Underdog", "Minimum rival margin",
                    "Only shadow markets where the rival's flips clear this margin", 25, 5, 500, Kind.PERCENT),
            new Setting("underdog.min_flips", "Underdog", "Minimum rival flips",
                    "Reconstructed flips a market needs in a day before it is shadowed", 3, 1, 50, Kind.COUNT),
            new Setting("underdog.max_targets", "Underdog", "Targets watched",
                    "How many shadowed markets join the watch list at once", 20, 1, 20, Kind.COUNT),
            // Boxes
            new Setting("orders.enabled", "Orders", "Read the order house",
                    "Open /orders between trades and read every open buy order (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("orders.scan_min", "Orders", "Read every",
                    "Minutes between reads of the order house", 20, 2, 120, Kind.MINUTES),
            new Setting("orders.collect", "Orders", "Collect filled orders",
                    "Sweep Your Orders on the read timer and collect any that show fully delivered, even with no fill "
                            + "notice and even when Place bids is off (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("orders.deliver", "Orders", "Deliver to orders",
                    "Sell a bought stack into an order that pays at least its listing target instead of listing it (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("orders.source", "Orders", "Buy for orders",
                    "Watch the auction for stacks an open order pays more for than they cost (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("orders.max_targets", "Orders", "Order targets watched",
                    "How many order-backed markets join the watch list at once", 10, 1, 40, Kind.COUNT),
            new Setting("evasion.warn_blocks", "Evasion", "Warn when someone is within",
                    "The outer ring: anyone this close is named in the log and in chat, but nothing stops yet",
                    128, 0, 128, Kind.BLOCKS),
            new Setting("evasion.player_idle_sec", "Evasion", "Evasion waits while you play",
                    "Evasion does nothing until you have been away from the keyboard this long; moving, looking around or attacking counts as playing",
                    20, 0, 300, Kind.SECONDS),
            new Setting("evasion.on_damage", "Evasion", "Stop when damaged",
                    "Any drop in health stops the session at once, and leaves the server too when the inner ring is set (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("evasion.rtp_on_damage", "Evasion", "Escape the moment you're damaged",
                    "Taking damage is the surest sign someone is on you, so escape straight away instead of only pausing - "
                    + "the escape is /rtp (Escape by = teleport) or a disconnect (Escape by = leave), whichever is set, and "
                    + "fires as soon as no trade is mid-flight. Needs Stop when damaged and an Escape mode on (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("liveness.enabled", "Session", "Say when nothing is moving",
                    "Every other check here guards against doing the wrong thing; none of them notices doing "
                    + "nothing. This watches outcomes: stock held, session running, and yet nothing sold or listed "
                    + "for a long time (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("liveness.quiet_min", "Session", "Call it stalled after",
                    "Minutes with no sale and no new listing, while holding stock, before it says so. The median "
                    + "hold is a couple of minutes, so anything past this is a stall rather than a slow patch",
                    25, 5, 240, Kind.MINUTES),
            new Setting("liveness.alert", "Session", "Send a stall to Discord",
                    "Post the stall as an alert as well as saying it in chat and the log (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("liveness.self_heal", "Session", "Break out of a stall by itself",
                    "When a stall is called and an auction page or dialog is still open on screen, close it the way you "
                    + "would with Escape - the server-reopened stray page is the usual wedge, and closing it lets the "
                    + "loop carry on without anyone at the keyboard. Only ever closes a screen; never touches a trade "
                    + "mid-flight. Off by default because it acts on the live session (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("join.auto", "Session", "Join the server by itself",
                    "From the title screen, the server list or a disconnect, walk onto the server without being clicked. "
                    + "The server is added to the list if it is not there. Any other screen you open cancels it, and a "
                    + "stale login is still the launcher's problem, not this (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("join.delay_sec", "Session", "Wait before joining",
                    "Quiet time on the title screen before it connects, so a game opened for another reason can be used",
                    8, 2, 120, Kind.SECONDS),
            new Setting("join.retry_sec", "Session", "Try again after",
                    "Gap before another attempt when a connection failed or dropped", 60, 15, 900, Kind.SECONDS),
            new Setting("session.auto_respawn_sec", "Session", "Respawn after dying",
                    "Seconds on the death screen before the client respawns itself, so an unattended session is not stopped by it; 0 waits for you",
                    10, 0, 300, Kind.SECONDS),
            new Setting("evasion.escape", "Evasion", "Escape by",
                    "What to do when someone crosses the inner ring: 0 nothing, 1 teleport away with /rtp, 2 leave the server. Either way the trade in flight finishes first. "
                    + "Ships as 0: teleports are refused outright because on DonutSMP they leave the connection one-way, and leaving the server while a player is "
                    + "near is how you lose an inventory to a combat log. Pausing is what actually worked",
                    1, 0, 2, Kind.COUNT),
            new Setting("evasion.disconnect_blocks", "Evasion", "Escape when within",
                    "Someone this close makes the client escape, not just pause; it always finishes the trade in flight first. Fire it early: the teleport takes a few seconds. 0 never escapes",
                    120, 0, 128, Kind.BLOCKS),
            new Setting("evasion.line_of_sight", "Evasion", "Only mobs that can see you",
                    "Ignore a mob with a wall between it and you. Inside a sealed room the ones outside cannot reach you, "
                    + "and treating them as threats makes the escape fire for nothing (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("evasion.ranged_blocks", "Evasion", "Escape from archers within",
                    "A skeleton, witch, ghast or blaze this far away, and facing you, counts as a threat: distance is no protection from an arrow. 0 ignores them",
                    0, 0, 64, Kind.BLOCKS),
            new Setting("evasion.projectile_blocks", "Evasion", "Escape from arrows within",
                    "An arrow, potion or fireball already flying towards you within this range triggers the escape at once. 0 ignores projectiles",
                    0, 0, 48, Kind.BLOCKS),
            new Setting("evasion.use_homes", "Evasion", "Escape to a home",
                    "Run to one of the homes in homes.json rather than a random teleport, never the same one twice running (1 on, 0 off)",
                    0, 0, 1, Kind.TOGGLE),
            new Setting("evasion.mob_blocks", "Evasion", "Escape from hostile mobs within",
                    "A hostile mob this close triggers the same escape as a player; keep it short, or a wandering spawn will teleport you all night. 0 ignores mobs, and taking damage always reacts",
                    0, 0, 24, Kind.BLOCKS),
            new Setting("evasion.contact_sec", "Evasion", "React when someone is this close in time",
                    "Anyone who would reach you within this many seconds at their current closing speed is handled at once, whatever ring they are in. An elytra crosses fifty blocks in about a second",
                    6, 1, 30, Kind.SECONDS),
            new Setting("evasion.glide_blocks", "Evasion", "Watch gliders from",
                    "A player gliding on an elytra this far out is already treated as closing", 120, 0, 200, Kind.BLOCKS),
            new Setting("recovery.give_up_after_min", "Slots", "Give up on a lost purchase after",
                    "A recovered purchase whose stack is nowhere in the inventory this long after it was "
                    + "bought is written off with no profit and no loss, so it stops blocking Resume. The money "
                    + "is gone either way; holding the session shut as well helps nobody. 0 never gives up",
                    180, 0, 2880, Kind.MINUTES),
            new Setting("shelf.after_min", "Stash", "Shelve a stack that has sat this long",
                    "How long a listing must have been up before the shelf will take it, and then only if the "
                    + "market has fallen below what it cost. Nine sales in ten happen within three minutes and "
                    + "ninety-six in a hundred within two hours, so anything still sitting well past that is "
                    + "not waiting for a buyer. 0 to shelve only at the bottom of the ladder",
                    120, 0, 1440, Kind.MINUTES),
            new Setting("shelf.min_gap_sec", "Stash", "Least time between shelf trips",
                    "A shelf trip is a visible physical act - a jump, a placement, a chest opening. Never do "
                    + "one more often than this, so it can never turn into a conspicuous loop. Three fruitless "
                    + "trips in a row also hold the shelf off for fifteen minutes",
                    60, 10, 600, Kind.SECONDS),
            new Setting("shelf.place_in_chest", "Stash", "Carry shelved stock to an ender chest",
                    "When on, the bot places an ender chest and physically deposits stock it has set aside. "
                    + "When off, the stock simply waits in the pack, still off the auction house and safe from "
                    + "the loss ladder - the chest run is a convenience, not the protection (1 on, 0 off)",
                    0, 0, 1, Kind.TOGGLE),
            new Setting("shelf.instead_of_loss", "Stash", "Shelve rather than sell under cost",
                    "When a pull-back would relist under what the stack cost, take it off the auction house and "
                    + "put it on the shelf instead. It keeps its cost and comes back the moment the market clears "
                    + "it. A shelf costs no slot and no fee, so waiting is free and free beats a booked loss "
                    + "(1 on, 0 off)",
                    0, 0, 1, Kind.TOGGLE),
            new Setting("stash.pocket_slot", "Stash", "Pocket kept for the chest",
                    "The one inventory slot the trading never reaches into, counted from zero, where the "
                    + "ender chest waits. Without it the desk finds a chest in the pack, correctly sees a "
                    + "thing it trades, and sells the key to the shelf",
                    35, 9, 35, Kind.COUNT),
            new Setting("stash.hotbar_slot", "Stash", "Hotbar slot for the chest",
                    "Which hotbar slot a chest is brought forward into, counted from zero. It holds one only "
                    + "for the moment it takes to place it. Keep it clear of the slot the trading uses",
                    8, 0, 8, Kind.COUNT),
            new Setting("stash.restock_below", "Stash", "Restock the reserve below",
                    "Buy another stack of ender chests when this few are left inside. Running the reserve to "
                    + "nothing is the one way to strand the bot: no chest to place, and no way to reach the "
                    + "ones inside without placing one",
                    8, 0, 64, Kind.COUNT),
            new Setting("pace.react_min_sec", "Pace", "Shortest pause before touching a new screen",
                    "A container that has just opened is not touched before this. Somebody has to see the window, "
                    + "find the slot and move the mouse to it, and clicking on the tick it arrives is the loudest "
                    + "thing in the sequence",
                    0.4, 0.1, 3, Kind.SECONDS),
            new Setting("pace.react_max_sec", "Pace", "Usual longest pause before touching a new screen",
                    "The top of the ordinary band; about one in seven runs past it", 1.2, 0.2, 6, Kind.SECONDS),
            new Setting("stash.enabled", "Stash", "Let the bot touch the world",
                    "The stash desk right-clicks a chest that is already there and reads what is inside. It is the "
                    + "only thing in the mod that interacts with the world rather than with a menu the server "
                    + "opened, so it stays off until it is asked for (1 on, 0 off)",
                    0, 0, 1, Kind.TOGGLE),
            new Setting("pace.click_gap_min_sec", "Pace", "Shortest gap between clicks",
                    "No two container clicks land closer together than this. One a tick is twenty a second, which "
                    + "breaks no rule and is still nothing a hand does",
                    0.15, 0.05, 2, Kind.SECONDS),
            new Setting("pace.click_gap_max_sec", "Pace", "Usual longest gap between clicks",
                    "The top of the ordinary band. About one click in ten runs past it - real clicking is mostly "
                    + "quick with a long tail, and a flat random band between two bounds is its own signature",
                    0.6, 0.1, 5, Kind.SECONDS),
            new Setting("safety.teleports_work", "Evasion", "Teleports work on this server",
                    "Whether /rtp and /home can be sent. Off for most of 2026-09: a teleport left the connection "
                    + "one-way, so the client kept sending and the server had stopped listening - invisible from "
                    + "inside, and it looks exactly like a quiet market. Retested and working 2026-09-08. Turn it "
                    + "off the moment a teleport costs a connection again (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("evasion.wander_every_min", "Evasion", "Move on every",
                    "Teleport somewhere new this often even with nobody about, give or take half the interval. "
                    + "The escape teleport only fires once you have been noticed; standing in one hole all day is "
                    + "what gets you noticed. Nothing is interrupted - the bot trades through commands and never "
                    + "touches the world. 0 to stay put",
                    45, 0, 360, Kind.MINUTES),
            new Setting("evasion.rtp_wait_sec", "Evasion", "Teleport warm-up",
                    "How long the server takes to move you after the teleport command; the client leaves the server if nothing has happened by then",
                    10, 2, 60, Kind.SECONDS),
            new Setting("evasion.rtp_if_underwater", "Evasion", "Re-teleport if underwater",
                    "When a random teleport lands the bot under water - common on a flooded server, where it cannot "
                    + "reach the auction and drowns - teleport again for dry ground, up to a few tries (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("evasion.rtp_abort_blocks", "Evasion", "Give up on the teleport within",
                    "Anyone this close while the teleport is warming up means it will not save us: leave the server at once",
                    8, 1, 24, Kind.BLOCKS),
            new Setting("evasion.reconnect_sec", "Evasion", "Come back after",
                    "Seconds off the server before the client rejoins by itself; 0 stays off until you return", 180, 0, 1800, Kind.SECONDS),
            new Setting("hud.only_when_live", "HUD", "Only when the feed is live",
                    "Keep the HUD hidden until the API key works and real market data is flowing (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("warmup.enabled", "Session", "Watch before trading",
                    "A fresh install watches the market until it has prices it can trust, and refuses to start before then (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("warmup.markets", "Session", "Markets before trading",
                    "How many markets need a price with real samples behind it before the session may start", 20, 1, 90, Kind.COUNT),
            new Setting("warmup.samples", "Session", "Samples per market",
                    "Completed sales a market needs before it counts towards the warm-up", 8, 1, 60, Kind.COUNT),
            new Setting("quiet.enabled", "Buying", "Quiet-market mode",
                    "When your own sales slow right down, take smaller and steadier trades and lean on the order desk (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("quiet.share_pct", "Buying", "Quiet below",
                    "Sales in the last 45 minutes, as a share of the usual rate, under which the market counts as quiet",
                    30, 5, 100, Kind.PERCENT),
            new Setting("quiet.relax_pct", "Buying", "Quiet relaxation",
                    "How far the minimum profit and ROI come down while the market is quiet", 30, 0, 60, Kind.PERCENT),
            new Setting("quiet.bid_boost", "Orders", "Quiet bid boost",
                    "Order slots the desk may use while the auction is quiet, as a multiple of the usual", 2, 1, 3, Kind.FACTOR),
            new Setting("demand.population", "Buying", "Server population",
                    "Weigh candidates by how the number of players online now compares with the population that usually trades (1 on, 0 off)",
                    1, 0, 1, Kind.TOGGLE),
            new Setting("panel.hold_idle_sec", "Session", "Hold while the screen is open",
                    "The bot waits while you have the DoughBay screen open, and carries on this many seconds after your last click or keypress (0 never waits)",
                    45, 0, 900, Kind.SECONDS),
            new Setting("session.auto_resume", "Session", "Auto-resume a recovered session",
                    "After a restart the session waits at a pause for the Resume button, so a person can confirm the "
                    + "retained spend caps. With this on it resumes itself once it is back in the server and the feed "
                    + "is healthy - only the clean pause; one still holding unresolved exposure always waits for you "
                    + "(1 auto, 0 wait for Resume)", 1, 0, 1, Kind.TOGGLE),
            new Setting("session.resume_after_leave", "Session", "Resume after leaving the server",
                    "Leaving the server pauses the session; 1 lets it resume by itself once you are back on, 0 waits for you to press Resume", 0, 0, 1, Kind.TOGGLE),
            new Setting("orders.bid", "Orders", "Place bids",
                    "Post your own buy orders on liquid markets under the quick-sale price and relist the fills (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("orders.bid_slots", "Orders", "Bid slots used",
                    "Most of your order slots the bot may fill with its own bids", 20, 1, 90, Kind.COUNT),
            new Setting("orders.depth_share_pct", "Orders", "Order size against market depth",
                    "The most of a market's own daily volume one order may ask for. A flat order size is sixteen "
                    + "minutes of demand in a busy market and five days of it in a thin one; this scales the ask to "
                    + "what the market actually clears. 100 disables it and uses the stack count alone",
                    5, 1, 100, Kind.PERCENT),
            new Setting("orders.bid_stacks", "Orders", "Stacks per bid",
                    "How many stacks each bid asks for", 3, 1, 4, Kind.COUNT),
            new Setting("upload.enabled", "Orders", "Send order sweeps home",
                    "Post every order-house sweep to the DoughBay API so it can serve an order book the public "
                    + "feed does not have. Needs the API's ingest route (1 on, 0 off)", 0, 0, 1, Kind.TOGGLE),
            new Setting("orders.bid_min_confidence", "Orders", "Bid: minimum confidence",
                    "How sure the price has to be before the desk bids on a market. Our own ledger says a lower bar "
                    + "pays: stacks bought under 0.4 returned more, and sold faster, than the ones above it",
                    0.2, 0, 1, Kind.RATE),
            new Setting("orders.bid_min_samples", "Orders", "Bid: minimum sales seen",
                    "Completed sales the market must have on record before the desk will bid on it",
                    15, 1, 200, Kind.COUNT),
            new Setting("orders.bid_min_sales_per_hour", "Orders", "Bid: minimum demand",
                    "Completed sales per hour a market needs before the bot bids on it", 0.25, 0, 50, Kind.RATE),
            new Setting("inventory.sweep_enabled", "Orders", "Auto-list leftover stock",
                    "Every so often, sweep the pack and list any sellable stack the normal path left behind - stock "
                    + "collected from an order that never got listed, or an item blacklisted after it was bought. The "
                    + "list-inventory key does the same on demand. (1 on, 0 off)", 1, 0, 1, Kind.TOGGLE),
            new Setting("inventory.sweep_min", "Orders", "Auto-list every",
                    "Minutes between automatic pack sweeps for leftover stock", 15, 1, 120, Kind.MINUTES),
            new Setting("orders.bid_min_margin_pct", "Orders", "Bid: minimum margin",
                    "The margin the desk must be able to make before it bids, over and above the global ROI floor. An "
                    + "order fills as the price softens into it, so a thin-margin bid relists at breakeven and dusts the "
                    + "book; a higher floor here keeps the desk on stock with room to move. 0 uses the global ROI floor",
                    3, 0, 80, Kind.PERCENT),
            new Setting("orders.bid_min_value", "Orders", "Bid: skip stacks worth under",
                    "The desk will not place an order on a stack whose quick-sale value is below this. Cheap commodity "
                    + "orders fill just as the price softens and end up relisted at breakeven, so a floor keeps the book "
                    + "on stock worth holding. 0 turns the floor off", 0, 0, 50000000, Kind.COUNT),
            new Setting("orders.bid_max_value", "Orders", "Bid: skip stacks worth over",
                    "The desk will not place an order on a stack whose quick-sale value is above this, so a single "
                    + "order cannot tie up (or sink) a large sum the way a high-value bid can. High-value stock still "
                    + "goes through deliberate buys. 0 turns the cap off", 0, 0, 200000000, Kind.COUNT),
            new Setting("orders.bid_max_hours", "Orders", "Cancel unfilled bids after",
                    "Hours a bid may sit unfilled before it is pulled", 12, 1, 160, Kind.COUNT),
            new Setting("orders.full_sweep_min", "Orders", "Full sweep every",
                    "Minutes between reads of the whole order house, every page to the last", 720, 10, 720, Kind.MINUTES),
            new Setting("orders.pages", "Orders", "Pages to read",
                    "How many pages of the newest orders each read covers; reads accumulate over the hour", 10, 1, 10, Kind.COUNT),
            new Setting("boxes.enabled", "Boxes", "Trade filled boxes",
                    "Buy shulker boxes and bundles priced below their contents and relist them (1 on, 0 off)",
                    0, 0, 1, Kind.TOGGLE),
            new Setting("boxes.max_open", "Boxes", "Boxes up at once",
                    "Most filled boxes that may be listed at the same time, one of each contents", 8, 1, 20, Kind.COUNT),
            new Setting("boxes.min_value", "Boxes", "Minimum box value ($)",
                    "Ignore boxes whose contents are worth less than this", 20000, 1000, 10000000, Kind.COUNT),
            new Setting("boxes.sweep_min", "Boxes", "Sweep the box market every",
                    "Minutes between deliberate looks at the filled-box market. Boxes were only ever noticed by "
                    + "accident, on pages read for something else; this goes and looks. 0 never sweeps",
                    120, 0, 240, Kind.MINUTES),
            new Setting("boxes.sweep_pages", "Boxes", "Pages per sweep",
                    "How deep each sweep reads. Boxes sit unsold for the better part of a day, so the underpriced "
                    + "ones pile up well past page one; a deep pass is worth more than a frequent shallow one",
                    20, 1, 60, Kind.COUNT),
            new Setting("boxes.min_confidence_pct", "Boxes", "Minimum pricing confidence",
                    "Every stack inside must be priced at least this confidently", 60, 0, 100, Kind.PERCENT)
    );

    /** Free-text settings: item allow and deny lists. */
    public record ListSetting(String key, String label, String description) {
    }

    public static final List<ListSetting> LISTS = List.of(
            new ListSetting("webhook.url", "Status webhook",
                    "A Discord webhook URL. Channel settings, Integrations, New Webhook, Copy Webhook URL. The hourly "
                    + "report is posted there: trades, profit, payments, what is listed, the population and the rivals. "
                    + "Leave it blank to post nothing"),
            new ListSetting("boxes.sweep_term", "Box markets to sweep",
                    "What the sweep searches for, separated by commas: \"shulker, bundle\". Every one is read each "
                    + "time the sweep comes round, one look each. Blank sweeps shulkers only"),
            new ListSetting("join.address", "Server to join by itself",
                    "The address the auto join connects to, e.g. donutsmp.net. Blank uses the last server this client "
                    + "was on, which is usually what you want"),
            new ListSetting("watch.filter", "Auction pages to watch",
                    "The sorts this client reads, in turn, separated by commas: \"Recently Listed, Lowest Price\". "
                    + "Recently Listed catches a mispriced item as it lands; Lowest Price catches the ones that landed "
                    + "while nobody was looking. Every look moves to the next sort. Blank means Recently Listed only"),
            new ListSetting("watch.tracked", "Tracked markets (watch list)",
                    "The markets the bot watches and may trade, edited from the Items tab in game and applied without a "
                    + "restart. Empty falls back to the tracked commodities set in config.json."),
            new ListSetting("items.allow", "Only trade these items",
                    "Comma-separated item names or ids, e.g. redstone, ender_pearl. Empty means everything."),
            new ListSetting("items.deny", "Never trade these items",
                    "Comma-separated item names or ids that are always skipped."),
            new ListSetting("items.liquidate", "Sell off and stop buying these",
                    "Comma-separated item names or ids to clear out: they are never bought, but stock "
                    + "you already hold is pulled back and relisted down to the market even below cost until "
                    + "it is gone. For unwinding a market you no longer want, without freezing the stock in it."),
            new ListSetting("items.gear", "Gear markets traded as commodities",
                    "Items whose enchanted copies are near-identical, so the plain market's price "
                    + "is the right price for them, e.g. netherite_pickaxe. Damaged ones are never "
                    + "traded whatever is listed here. Empty means gear is left alone."));

    private static final Map<String, Setting> BY_KEY = new ConcurrentHashMap<>();
    private static final Map<String, Double> VALUES = new ConcurrentHashMap<>();
    private static final Map<String, String> TEXTS = new ConcurrentHashMap<>();
    private static volatile Path file;

    public static String text(String key) {
        return TEXTS.getOrDefault(key, "");
    }

    public static void setText(String key, String value) {
        TEXTS.put(key, value == null ? "" : value.strip());
        save();
    }

    /** Item ids without the namespace, parsed from a comma-separated list. */
    public static java.util.Set<String> itemSet(String key) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String token : text(key).split("[,;\\s]+")) {
            String id = normalizeItem(token);
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    public static String normalizeItem(String value) {
        if (value == null) return "";
        String id = value.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        int colon = id.indexOf(':');
        if (colon >= 0) id = id.substring(colon + 1);
        int hash = id.indexOf('#');
        if (hash >= 0) id = id.substring(0, hash);
        return id;
    }

    /**
     * Items whose value lives in parts the Donut feed does not report, so
     * their completed-sale statistics mix things that are not the same
     * market: a tipped arrow of harming and one of slow falling share an id.
     */
    private static final java.util.Set<String> FEED_BLIND = java.util.Set.of(
            "minecraft:tipped_arrow", "minecraft:potion", "minecraft:splash_potion", "minecraft:lingering_potion",
            "minecraft:enchanted_book", "minecraft:written_book", "minecraft:filled_map", "minecraft:firework_rocket",
            "minecraft:player_head", "minecraft:suspicious_stew", "minecraft:ominous_bottle");

    /** Named bundles of values; applying one sets those keys and leaves the rest as they are. */
    public static final java.util.LinkedHashMap<String, java.util.Map<String, Double>> PRESETS = new java.util.LinkedHashMap<>();

    static {
        PRESETS.put("Careful", java.util.Map.ofEntries(
                java.util.Map.entry("buy.purchase_cap_pct", 5.0), java.util.Map.entry("buy.outstanding_cap_pct", 50.0),
                java.util.Map.entry("buy.max_open_per_market", 2.0),
                java.util.Map.entry("pace.gap_min_sec", 2.5), java.util.Map.entry("pace.gap_max_sec", 7.0),
                java.util.Map.entry("pace.long_gap_min_sec", 10.0), java.util.Map.entry("pace.long_gap_max_sec", 20.0),
                java.util.Map.entry("pace.long_gap_one_in", 6.0),
                java.util.Map.entry("tier.large_max_open_per_market", 1.0), java.util.Map.entry("tier.spill_min_roi_pct", 60.0),
                java.util.Map.entry("rival.mirror_when_slow", 0.0), java.util.Map.entry("underdog.max_targets", 4.0),
                java.util.Map.entry("boxes.enabled", 0.0), java.util.Map.entry("demand.dead_pct", 50.0)));
        PRESETS.put("Aggressive", java.util.Map.ofEntries(
                java.util.Map.entry("buy.purchase_cap_pct", 8.0), java.util.Map.entry("buy.outstanding_cap_pct", 80.0),
                java.util.Map.entry("buy.max_open_per_market", 5.0),
                java.util.Map.entry("pace.gap_min_sec", 1.5), java.util.Map.entry("pace.gap_max_sec", 4.0),
                java.util.Map.entry("pace.long_gap_min_sec", 6.0), java.util.Map.entry("pace.long_gap_max_sec", 10.0),
                java.util.Map.entry("pace.long_gap_one_in", 12.0),
                java.util.Map.entry("tier.large_max_open_per_market", 2.0), java.util.Map.entry("tier.spill_min_roi_pct", 40.0),
                java.util.Map.entry("rival.quiet_extra_small_slots", 10.0), java.util.Map.entry("rival.mirror_when_slow", 1.0),
                java.util.Map.entry("underdog.max_targets", 20.0), java.util.Map.entry("boxes.enabled", 1.0),
                java.util.Map.entry("demand.dead_pct", 35.0)));
        PRESETS.put("Overnight", java.util.Map.ofEntries(
                java.util.Map.entry("buy.outstanding_cap_pct", 70.0), java.util.Map.entry("buy.max_open_per_market", 3.0),
                java.util.Map.entry("pace.gap_min_sec", 2.0), java.util.Map.entry("pace.gap_max_sec", 6.0),
                java.util.Map.entry("reprice.small_after_min", 15.0), java.util.Map.entry("reprice.mid_after_min", 30.0),
                java.util.Map.entry("reprice.large_after_min", 45.0),
                java.util.Map.entry("rival.mirror_when_slow", 1.0), java.util.Map.entry("demand.dead_pct", 25.0),
                java.util.Map.entry("evasion.radius_blocks", 32.0), java.util.Map.entry("underdog.max_targets", 10.0)));
    }

    /** Applies a preset by name; "Default" resets every numeric setting. Returns how many settings changed. */
    public static synchronized int applyPreset(String name) {
        int changed = 0;
        if ("Default".equalsIgnoreCase(name)) {
            for (Setting s : SETTINGS) {
                if (!isDefault(s.key())) {
                    reset(s.key());
                    changed++;
                }
            }
            return changed;
        }
        java.util.Map<String, Double> values = PRESETS.get(name);
        if (values == null) return 0;
        for (java.util.Map.Entry<String, Double> e : values.entrySet()) {
            if (BY_KEY.containsKey(e.getKey()) && get(e.getKey()) != e.getValue()) {
                set(e.getKey(), e.getValue());
                changed++;
            }
        }
        return changed;
    }

    /** Whether the allow and deny lists let this item be traded. */
    /**
     * Whether an enchanted copy of this item may be traded at the plain
     * market's price.
     *
     * <p>Only true where the market has standardised on one kit. A netherite
     * pickaxe is a commodity in practice - nearly every one listed carries the
     * same four enchantments and they clear within a few percent of each other
     * - so the plain market's statistics are that item's statistics. A mace is
     * not: dozens of different builds at wildly different prices, and pricing
     * one off the others would be guessing.
     *
     * <p>Deliberately a list somebody has to fill in rather than something
     * inferred, because being wrong about it means listing a valuable thing at
     * a common thing's price.
     */
    public static boolean gearMarket(String itemId) {
        return itemSet("items.gear").contains(normalizeItem(itemId));
    }

    /**
     * Items being cleared out: never bought, but existing stock is still
     * pulled back and relisted down to the market even below cost until gone.
     * The deny list only stops buying; this also unfreezes the sell side.
     */
    public static boolean liquidating(String itemId) {
        return setMatches("items.liquidate", itemId);
    }

    /**
     * Whether {@code itemId} is named by the list at {@code key}. A token
     * matches the exact id or, as a category, any id ending in "_" + token -
     * so "banner" covers white_banner, red_banner and the rest. Plain set
     * membership matched only the exact token, so a category name like
     * "banner" silently matched nothing and the list did quietly nothing.
     */
    public static boolean setMatches(String key, String itemId) {
        String id = normalizeItem(itemId);
        for (String token : itemSet(key)) {
            if (id.equals(token) || id.endsWith("_" + token)) return true;
        }
        return false;
    }

    /** Adds one item id to a comma-separated list setting (normalized); no-op if already present. */
    public static void addToList(String key, String itemId) {
        String id = normalizeItem(itemId);
        if (id.isEmpty()) return;
        java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
        for (String token : text(key).split("[,;\\s]+")) {
            String n = normalizeItem(token);
            if (!n.isEmpty()) tokens.add(n);
        }
        if (tokens.add(id)) setText(key, String.join(", ", tokens));
    }

    /** Removes one item id from a comma-separated list setting; no-op if absent. */
    public static void removeFromList(String key, String itemId) {
        String id = normalizeItem(itemId);
        java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
        boolean changed = false;
        for (String token : text(key).split("[,;\\s]+")) {
            String n = normalizeItem(token);
            if (n.isEmpty()) continue;
            if (n.equals(id)) {
                changed = true;
                continue;
            }
            tokens.add(n);
        }
        if (changed) setText(key, String.join(", ", tokens));
    }

    public static boolean itemAllowed(String itemId) {
        String id = normalizeItem(itemId);
        if (FEED_BLIND.contains(id)) return false;
        if (setMatches("items.deny", id)) return false;
        if (setMatches("items.liquidate", id)) return false;
        return itemSet("items.allow").isEmpty() || setMatches("items.allow", id);
    }

    static {
        for (Setting s : SETTINGS) BY_KEY.put(s.key(), s);
    }

    private Tuning() {
    }

    public static Setting setting(String key) {
        Setting s = BY_KEY.get(key);
        if (s == null) throw new IllegalArgumentException("Unknown tuning key " + key);
        return s;
    }

    /** The current value, or the default when nothing was saved. */
    public static double get(String key) {
        Setting s = setting(key);
        Double v = VALUES.get(key);
        return v == null ? s.defaultValue() : s.clamp(v);
    }

    public static long millis(String minutesOrSecondsKey) {
        Setting s = setting(minutesOrSecondsKey);
        double v = get(minutesOrSecondsKey);
        return switch (s.kind()) {
            case MINUTES -> Math.round(v * 60_000.0);
            case SECONDS -> Math.round(v * 1000.0);
            default -> Math.round(v);
        };
    }

    public static boolean isDefault(String key) {
        return Math.abs(get(key) - setting(key).defaultValue()) < 1e-9;
    }

    /** Clamps, applies, and saves; returns the value actually stored. */
    public static double set(String key, double value) {
        Setting s = setting(key);
        double clamped = s.clamp(value);
        VALUES.put(key, clamped);
        save();
        return clamped;
    }

    public static void reset(String key) {
        VALUES.remove(key);
        save();
    }

    public static List<String> groups() {
        List<String> out = new ArrayList<>();
        for (Setting s : SETTINGS) if (!out.contains(s.group())) out.add(s.group());
        return out;
    }

    public static void load(Path directory) {
        file = directory.resolve("tuning.json");
        Path f = file;
        seenModifiedAt = modifiedAt(f);
        if (!Files.exists(f)) return;
        try {
            JsonNode root = new ObjectMapper().readTree(Files.readString(f, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) return;
            Iterator<Map.Entry<String, JsonNode>> it = root.fields();
            int loaded = 0;
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getValue().isTextual()) {
                    for (ListSetting l : LISTS) {
                        if (l.key().equals(e.getKey())) {
                            TEXTS.put(l.key(), e.getValue().asText());
                            loaded++;
                        }
                    }
                    continue;
                }
                Setting s = BY_KEY.get(e.getKey());
                if (s == null || !e.getValue().isNumber()) continue;
                VALUES.put(s.key(), s.clamp(e.getValue().asDouble()));
                loaded++;
            }
            DoughBayClient.LOGGER.info("DoughBay tuning: {} setting(s) loaded from {}", loaded, f);
        } catch (IOException | RuntimeException e) {
            DoughBayClient.LOGGER.warn("DoughBay tuning could not be read from {}: {}", f, e.toString());
        }
        loadLocalOverrides();
    }

    /**
     * Settings that belong to this client alone.
     *
     * <p>Two clients may share one config folder, and then one tuning.json, so
     * that they share a ledger and an API key. Some settings cannot be shared:
     * how many auction slots this account's rank allows, and whether this
     * client trades or only scans. Those go in {@code doughbay-local.json} in
     * the game folder, which is never shared, and win over the shared file.
     */
    private static void loadLocalOverrides() {
        Path local;
        try {
            local = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("doughbay-local.json");
        } catch (Throwable e) {
            return;   // not running inside the game, as in the tests
        }
        localFile = local;
        seenLocalModifiedAt = modifiedAt(local);
        if (!Files.exists(local)) return;
        try {
            JsonNode root = new ObjectMapper().readTree(Files.readString(local, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) return;
            int loaded = 0;
            Iterator<Map.Entry<String, JsonNode>> it = root.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getValue().isTextual()) {
                    for (ListSetting l : LISTS) {
                        if (l.key().equals(e.getKey())) {
                            TEXTS.put(l.key(), e.getValue().asText());
                            loaded++;
                        }
                    }
                    continue;
                }
                Setting s = BY_KEY.get(e.getKey());
                if (s == null || !e.getValue().isNumber()) continue;
                VALUES.put(s.key(), s.clamp(e.getValue().asDouble()));
                loaded++;
            }
            if (loaded > 0) {
                DoughBayClient.LOGGER.info("DoughBay tuning: {} setting(s) for this client only from {}", loaded, local);
            }
        } catch (IOException | RuntimeException e) {
            DoughBayClient.LOGGER.warn("DoughBay could not read {}: {}", local, e.toString());
        }
    }

    private static volatile Path localFile;
    private static volatile long seenLocalModifiedAt;

    private static volatile long seenModifiedAt;

    private static long modifiedAt(Path f) {
        if (f == null) return 0;
        try {
            return Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Picks up edits made to tuning.json outside the game, so a value can
     * be changed from a text editor, a script, or a remote console while
     * the game runs. Called from the client tick every couple of seconds.
     */
    public static void pollExternalChanges() {
        Path f = file;
        if (f == null) return;
        long now = modifiedAt(f);
        long nowLocal = modifiedAt(localFile);
        if (now == seenModifiedAt && nowLocal == seenLocalModifiedAt) return;
        seenLocalModifiedAt = nowLocal;
        if (now == seenModifiedAt) {
            // Only this client's own overrides moved.
            loadLocalOverrides();
            return;
        }
        seenModifiedAt = now;
        if (now == 0) return;
        Map<String, Double> beforeValues = Map.copyOf(VALUES);
        Map<String, String> beforeTexts = Map.copyOf(TEXTS);
        VALUES.clear();
        TEXTS.clear();
        load(f.getParent());
        int changed = 0;
        for (Setting s : SETTINGS) {
            if (!java.util.Objects.equals(beforeValues.get(s.key()), VALUES.get(s.key()))) changed++;
        }
        for (ListSetting l : LISTS) {
            if (!java.util.Objects.equals(beforeTexts.get(l.key()), TEXTS.get(l.key()))) changed++;
        }
        if (changed > 0) {
            DoughBayClient.LOGGER.info("DoughBay tuning: {} setting(s) changed on disk and applied", changed);
        }
    }

    private static synchronized void save() {
        Path f = file;
        if (f == null) return;
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            // Write every registered setting, value or default, so a key can
            // never silently fall out of the file and revert on the next load.
            // Omitting the ones still at default is how settings.json kept
            // losing whole groups: a key absent from the file loads as nothing,
            // saves as nothing, and is gone for good.
            for (Setting s : SETTINGS) {
                root.put(s.key(), get(s.key()));
            }
            for (ListSetting l : LISTS) {
                root.put(l.key(), text(l.key()));
            }
            Files.createDirectories(f.getParent());
            Files.writeString(f, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    StandardCharsets.UTF_8);
            seenModifiedAt = modifiedAt(f);
        } catch (IOException | RuntimeException e) {
            DoughBayClient.LOGGER.warn("DoughBay tuning could not be saved to {}: {}", f, e.toString());
        }
    }
}

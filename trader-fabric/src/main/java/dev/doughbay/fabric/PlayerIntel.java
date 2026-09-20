package dev.doughbay.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is nearby, what they are wearing, and what the server knows about them.
 *
 * <p>The client is already told every nearby player's equipment: the server
 * has to send it for them to be drawn at all, so armour, held items, damage
 * and enchantments are readable without asking for anything. That says how
 * dangerous somebody is right now. What it does not say is whether they are a
 * fighter, and the profile behind {@code /stats} does: kills against deaths,
 * playtime, money. Together they answer the only question that matters when a
 * stranger walks up to an unattended trader.
 *
 * <p>Grown from a standalone probe written on 2026-09-04, kept in its shape:
 * every read is guarded, because a mapping that moved should cost one line of
 * the panel rather than the client.
 */
public final class PlayerIntel {
    /** A profile is asked for once and kept this long; players do not change class in an hour. */
    private static final long STATS_TTL_MILLIS = 30 * 60_000L;
    /** Never ask about the same name twice inside this window, even when the answer was nothing. */
    private static final long RETRY_MILLIS = 5 * 60_000L;

    private static final Map<String, Profile> PROFILES = new ConcurrentHashMap<>();
    private static final Map<String, Long> ASKED_AT = new ConcurrentHashMap<>();

    /**
     * The names the render thread last saw nearby.
     *
     * <p>Only the render thread may walk the level's player list; the feed
     * thread that does the asking cannot. So the panel, which is already
     * looking, writes down who it saw, and the feed reads that instead. Doing
     * it the other way round returned an empty list nearly every time and no
     * profile was ever fetched.
     */
    private static volatile List<String> lastSeen = List.of();

    private PlayerIntel() {
    }

    /** What the server's profile says about a player; every field as the API gave it. */
    public record Profile(String name, long kills, long deaths, long playtimeMinutes,
                          long money, long mobsKilled, long at) {
        public double ratio() {
            return deaths <= 0 ? kills : kills / (double) deaths;
        }

        /** "142/38 K/D" style, or "" when the profile is not in yet. */
        public String killLine() {
            if (kills <= 0 && deaths <= 0) return "";
            return kills + "/" + deaths + "  " + String.format(Locale.ROOT, "%.1f", ratio());
        }
    }

    /** One worn or held item, ready to draw. */
    public record Gear(String slot, ItemStack stack, String name, int durabilityLeft, int durabilityMax,
                       List<String> enchantments) {
    }

    /** A player near us, with everything we can say about them. */
    public record Nearby(String name, int distance, float health, List<Gear> gear, Profile profile) {
    }

    /**
     * Players within range, nearest first. Reads only what the client already
     * holds; the profile is whatever has been fetched so far, or null.
     */
    public static List<Nearby> nearby(Minecraft client, double range, int limit) {
        List<Nearby> out = new ArrayList<>();
        try {
            if (client == null || client.player == null || client.level == null) return out;
            List<Player> players = new ArrayList<>();
            boolean hideAllies = Tuning.get("intel.hide_allies") >= 0.5;
            for (Player p : client.level.players()) {
                if (p == client.player) continue;
                // The panel is for strangers. Somebody on the ally list is
                // already known to be harmless, and a profile request for
                // them is a wasted call.
                if (hideAllies && SafeHomes.isAlly(safeName(p))) continue;
                if (p.distanceTo(client.player) <= range) players.add(p);
            }
            players.sort(Comparator.comparingDouble(p -> p.distanceTo(client.player)));
            List<String> seen = new ArrayList<>();
            for (Player p : players) {
                if (out.size() >= limit) break;
                String name = safeName(p);
                if (name.isEmpty()) continue;
                seen.add(name);
                out.add(new Nearby(name, Math.round(p.distanceTo(client.player)), safeHealth(p),
                        gearOf(p), PROFILES.get(name.toLowerCase(Locale.ROOT))));
            }
            lastSeen = List.copyOf(seen);
        } catch (Throwable ignored) {
            // A panel that cannot be drawn is not worth an exception in the renderer.
        }
        return out;
    }

    private static String safeName(Player p) {
        try {
            String n = p.getName().getString();
            return n == null ? "" : n;
        } catch (Throwable t) {
            return "";
        }
    }

    private static float safeHealth(Player p) {
        try {
            return p.getHealth();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Everything worn and held, empty slots skipped. */
    private static List<Gear> gearOf(Player p) {
        List<Gear> gear = new ArrayList<>();
        add(gear, "hand", safeStack(p, null, true));
        add(gear, "off", safeStack(p, null, false));
        add(gear, "head", safeStack(p, EquipmentSlot.HEAD, true));
        add(gear, "chest", safeStack(p, EquipmentSlot.CHEST, true));
        add(gear, "legs", safeStack(p, EquipmentSlot.LEGS, true));
        add(gear, "feet", safeStack(p, EquipmentSlot.FEET, true));
        return gear;
    }

    private static ItemStack safeStack(Player p, EquipmentSlot slot, boolean main) {
        try {
            if (slot != null) return p.getItemBySlot(slot);
            return main ? p.getMainHandItem() : p.getOffhandItem();
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private static void add(List<Gear> gear, String slot, ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return;
            String name = "item";
            try {
                name = stack.getHoverName().getString();
            } catch (Throwable ignored) {
                // an unnamed stack still draws
            }
            int max = 0;
            int left = 0;
            try {
                max = stack.getMaxDamage();
                if (max > 0) left = Math.max(0, max - stack.getDamageValue());
            } catch (Throwable ignored) {
                // damage is optional
            }
            gear.add(new Gear(slot, stack, name, left, max, enchantmentsOf(stack)));
        } catch (Throwable ignored) {
            // one unreadable slot is not worth losing the rest
        }
    }

    /** "protection IV" style names, in the order the component holds them. */
    private static List<String> enchantmentsOf(ItemStack stack) {
        List<String> out = new ArrayList<>();
        try {
            ItemEnchantments enchants = stack.get(DataComponents.ENCHANTMENTS);
            if (enchants == null || enchants.isEmpty()) return out;
            for (Holder<Enchantment> holder : enchants.keySet()) {
                int level = 0;
                try {
                    level = enchants.getLevel(holder);
                } catch (Throwable ignored) {
                    // an unreadable level still names the enchantment
                }
                String name = "enchanted";
                try {
                    if (holder.unwrapKey().isPresent()) {
                        name = holder.unwrapKey().get().identifier().toString();
                        int colon = name.indexOf(':');
                        if (colon >= 0 && colon + 1 < name.length()) name = name.substring(colon + 1);
                    } else {
                        String registered = holder.getRegisteredName();
                        if (registered != null && !registered.isBlank()) name = registered;
                    }
                } catch (Throwable ignored) {
                    // fall back to the generic name
                }
                out.add(name.replace('_', ' ') + " " + roman(level));
            }
        } catch (Throwable ignored) {
            // no enchantment component, or one that moved
        }
        return out;
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> Integer.toString(level);
        };
    }

    /**
     * Names near us whose profile we have not asked about lately. The feed
     * thread does the asking; this only decides who is worth a request, and
     * the answer is kept for half an hour.
     */
    public static List<String> profilesWanted() {
        List<String> want = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String name : lastSeen) {
            String key = name.toLowerCase(Locale.ROOT);
            Profile have = PROFILES.get(key);
            if (have != null && now - have.at() < STATS_TTL_MILLIS) continue;
            Long asked = ASKED_AT.get(key);
            if (asked != null && now - asked < RETRY_MILLIS) continue;
            want.add(name);
        }
        return want;
    }

    /** The feed thread reporting what {@code /stats} returned for a name. */
    public static void record(String name, Map<String, String> stats) {
        if (name == null || name.isBlank()) return;
        String key = name.toLowerCase(Locale.ROOT);
        ASKED_AT.put(key, System.currentTimeMillis());
        if (stats == null || stats.isEmpty()) return;
        PROFILES.put(key, new Profile(name,
                number(stats.get("kills")), number(stats.get("deaths")),
                number(stats.get("playtime")) / 60_000L,
                number(stats.get("money")), number(stats.get("mobs_killed")),
                System.currentTimeMillis()));
    }

    /** The API types every stat as a string, and formats them for a chat window. */
    private static long number(String raw) {
        if (raw == null) return 0;
        StringBuilder digits = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (Character.isDigit(c)) digits.append(c);
            else if (digits.length() > 0 && c != ',' && c != '.') break;
        }
        if (digits.length() == 0) return 0;
        try {
            return Long.parseLong(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Everything known, for the screen's own listing. */
    public static Map<String, Profile> profiles() {
        return Map.copyOf(new LinkedHashMap<>(PROFILES));
    }
}

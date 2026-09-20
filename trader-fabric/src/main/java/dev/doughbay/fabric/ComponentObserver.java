package dev.doughbay.fabric;

import dev.doughbay.storage.Database;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records every auction row that carries value beyond item and count:
 * enchanted gear and books, potions and tipped arrows, trimmed armor,
 * filled shulker boxes and bundles. Each sighting keeps the full
 * description, the asking price and the seller. The feed never shows
 * these parts, so this log is the only price history for them, and the
 * part values the valuer learns are fitted from it.
 */
public final class ComponentObserver {
    private static final long DEDUPE_MILLIS = 10 * 60_000L;
    private static volatile ComponentObserver instance;

    private final Path databasePath;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "doughbay-component-observer");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Long> recent = new ConcurrentHashMap<>();
    private final AtomicLong observed = new AtomicLong();

    private ComponentObserver(Path databasePath) {
        this.databasePath = databasePath;
    }

    public static void init(Path databasePath) {
        instance = new ComponentObserver(databasePath);
        instance.writer.submit(instance::loadLearnedParts);
    }

    public static long observedCount() {
        ComponentObserver o = instance;
        return o == null ? 0 : o.observed.get();
    }

    /**
     * Called for every row read on an auction page. Cheap for plain stacks:
     * the descriptor is built, found plain, and dropped.
     */
    public static void observeRow(ItemStack stack, long price, String seller, String page) {
        ComponentObserver o = instance;
        if (o == null || stack == null || stack.isEmpty() || price <= 0) return;
        ItemDescriptor d;
        try {
            d = ItemDescriptor.of(stack);
        } catch (RuntimeException e) {
            return;
        }
        if (!d.hasParts()) return;
        String key = d.key() + "|" + price + "|" + (seller == null ? "" : seller);
        long now = System.currentTimeMillis();
        Long last = o.recent.get(key);
        if (last != null && now - last < DEDUPE_MILLIS) return;
        o.recent.put(key, now);
        if (o.recent.size() > 5_000) o.recent.entrySet().removeIf(e -> now - e.getValue() > DEDUPE_MILLIS);
        String json = d.toJson();
        String descriptorKey = d.key();
        String sellerName = seller == null ? "" : seller;
        String pageName = page == null ? "" : page;
        o.writer.submit(() -> {
            try (Database db = new Database(o.databasePath);
                 PreparedStatement ps = db.connection().prepareStatement(
                         "INSERT INTO component_listings(observed_at, page, item_id, count, total_price, seller_name, "
                                 + "descriptor_key, descriptor_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, now);
                ps.setString(2, pageName);
                ps.setString(3, d.itemId());
                ps.setInt(4, d.count());
                ps.setLong(5, price);
                ps.setString(6, sellerName);
                ps.setString(7, descriptorKey);
                ps.setString(8, json);
                ps.executeUpdate();
                o.observed.incrementAndGet();
            } catch (Exception e) {
                DoughBayClient.LOGGER.warn("DoughBay could not record a component listing: {}", e.toString());
            }
        });
    }

    /** Learned part values persisted by the fitting pass; loaded once at start. */
    private void loadLearnedParts() {
        try (Database db = new Database(databasePath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "SELECT part_key, value, confidence FROM part_values");
             ResultSet rs = ps.executeQuery()) {
            int n = 0;
            while (rs.next()) {
                ComponentValuer.learn(rs.getString(1), rs.getLong(2), rs.getDouble(3));
                n++;
            }
            if (n > 0) DoughBayClient.LOGGER.info("DoughBay component valuer: {} learned part value(s)", n);
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay could not load learned part values: {}", e.toString());
        }
    }

    /** "Seller: name" from the row's tooltip, or "" when absent. */
    public static String sellerFrom(List<String> tooltipLines) {
        if (tooltipLines == null) return "";
        for (String line : tooltipLines) {
            String plain = line == null ? "" : line.strip();
            int at = plain.toLowerCase(Locale.ROOT).indexOf("seller");
            if (at < 0) continue;
            String rest = plain.substring(at + 6).replace(':', ' ').strip();
            int space = rest.indexOf(' ');
            return space > 0 ? rest.substring(0, space) : rest;
        }
        return "";
    }
}

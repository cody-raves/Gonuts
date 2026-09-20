package dev.doughbay.fabric;

import dev.doughbay.core.model.MarketStats;
import dev.doughbay.core.model.StackBucket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prices an {@link ItemDescriptor} by its parts, recursively: a plain
 * stack is the completed-sale median the ledger already knows; a shulker
 * box or bundle is the box plus its contents, discounted because a buyer
 * has to break it up; enchantments, potion effects and arrow effects are
 * priced from the learned part table, which fills in from observation.
 * A part with no evidence is left unpriced rather than guessed, and the
 * valuation says so, so the bot can never overpay for something it does
 * not understand.
 */
public final class ComponentValuer {
    /** Contents of a container are worth this much of their sum to a buyer. */
    private static final double CONTAINER_DISCOUNT = 0.90;
    private static final double PLAIN_CONFIDENCE = 0.9;
    private static final double SCALED_CONFIDENCE = 0.6;

    public record Part(String label, long value, boolean priced, double confidence) {
    }

    /**
     * @param total    the sum of the priced parts
     * @param complete whether every part that carries value was priced
     */
    public record Valuation(long total, boolean complete, double confidence, List<Part> parts) {
        public String summary() {
            StringBuilder out = new StringBuilder();
            for (Part p : parts) {
                if (out.length() > 0) out.append(", ");
                out.append(p.label()).append(' ').append(p.priced() ? "$" + p.value() : "unpriced");
            }
            return (complete ? "" : "[incomplete] ") + "$" + total + " = " + out;
        }
    }

    /** Learned values for enchantments, potions and effects; empty until observation fills it. */
    private static final Map<String, Part> LEARNED = new ConcurrentHashMap<>();

    public static void learn(String partKey, long value, double confidence) {
        LEARNED.put(partKey, new Part(partKey, value, true, confidence));
    }

    public static int learnedParts() {
        return LEARNED.size();
    }

    private ComponentValuer() {
    }

    public static Valuation value(ItemDescriptor d, MarketWatcher.Snapshot snapshot) {
        List<Part> parts = new ArrayList<>();
        boolean complete = true;
        double confidence = 1.0;
        long total = 0;

        Optional<Part> base = plainValue(d.itemId(), d.count(), snapshot);
        if (base.isPresent()) {
            parts.add(base.get());
            total += base.get().value();
            confidence = Math.min(confidence, base.get().confidence());
        } else {
            parts.add(new Part(ItemDescriptor.shortId(d.itemId()) + " x" + d.count(), 0, false, 0));
            complete = false;
        }
        for (Map.Entry<String, Integer> e : d.enchantments().entrySet()) {
            String key = "enchant:" + e.getKey() + ":" + e.getValue();
            Part learned = LEARNED.get(key);
            if (learned != null) {
                parts.add(new Part(ItemDescriptor.shortId(e.getKey()) + " " + e.getValue(), learned.value(), true, learned.confidence()));
                total += learned.value();
                confidence = Math.min(confidence, learned.confidence());
            } else {
                parts.add(new Part(ItemDescriptor.shortId(e.getKey()) + " " + e.getValue(), 0, false, 0));
                complete = false;
            }
        }
        if (!d.potion().isEmpty() || !d.effects().isEmpty()) {
            String key = "potion:" + d.itemId() + ":" + d.potion() + ":" + String.join("|", d.effects());
            Part learned = LEARNED.get(key);
            String label = d.potion().isEmpty() ? "effects" : ItemDescriptor.shortId(d.potion());
            if (learned != null) {
                parts.add(new Part(label, learned.value(), true, learned.confidence()));
                total += learned.value();
                confidence = Math.min(confidence, learned.confidence());
            } else {
                parts.add(new Part(label, 0, false, 0));
                complete = false;
            }
        }
        if (!d.trim().isEmpty()) {
            // Trims rarely move the price; noted, not valued.
            parts.add(new Part("trim", 0, true, 1.0));
        }
        if (d.isContainer()) {
            long inside = 0;
            for (ItemDescriptor child : d.contents()) {
                Valuation v = value(child, snapshot);
                if (!v.complete()) complete = false;
                inside += v.total();
                confidence = Math.min(confidence, v.confidence());
            }
            long discounted = Math.round(inside * CONTAINER_DISCOUNT);
            parts.add(new Part("contents x" + d.contents().size(), discounted, true, confidence));
            total += discounted;
        }
        return new Valuation(total, complete, complete ? confidence : 0, List.copyOf(parts));
    }

    /**
     * The plain-stack price: the exact bucket's median when the ledger has
     * it, otherwise a per-unit scaling from another bucket at lower
     * confidence, otherwise nothing.
     */
    static Optional<Part> plainValue(String itemId, int count, MarketWatcher.Snapshot snapshot) {
        if (snapshot == null || itemId == null) return Optional.empty();
        StackBucket bucket = StackBucket.of(count);
        MarketStats exact = null;
        MarketStats any = null;
        for (MarketStats stats : snapshot.markets()) {
            if (stats == null || !stats.hasPrices() || !itemId.equals(stats.itemKey())) continue;
            if (stats.bucket() == bucket && bucket != StackBucket.OTHER) exact = stats;
            if (any == null || stats.sampleCount() > any.sampleCount()) any = stats;
        }
        String label = ItemDescriptor.shortId(itemId) + " x" + count;
        if (exact != null) {
            return Optional.of(new Part(label, (long) Math.floor(exact.weightedMedian()), true, PLAIN_CONFIDENCE));
        }
        if (any != null && any.bucket().exactCount() > 0) {
            double perUnit = any.weightedMedian() / any.bucket().exactCount();
            return Optional.of(new Part(label + " (scaled)", (long) Math.floor(perUnit * count), true, SCALED_CONFIDENCE));
        }
        return Optional.empty();
    }

    static String money(long value) {
        if (value >= 1_000_000) return String.format(Locale.ROOT, "$%.2fm", value / 1_000_000.0);
        if (value >= 1_000) return String.format(Locale.ROOT, "$%.1fk", value / 1_000.0);
        return "$" + value;
    }
}

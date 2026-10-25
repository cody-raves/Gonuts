package dev.doughbay.fabric.automation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exact accepted placements. Local IDs identify receipts, not an inferred native row. */
final class AcceptedOrders {
    record Bid(String id, String itemId, long unitPrice, int count, long placedAt, long cancelledAt) {
        Bid(String id, String itemId, long unitPrice, int count, long placedAt) {
            this(id,itemId,unitPrice,count,placedAt,0);
        }
        Bid {
            if (id == null || !id.matches("[a-zA-Z0-9-]{1,80}") || itemId == null
                    || !itemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                    || unitPrice <= 0 || count <= 0 || placedAt < 0 || cancelledAt < 0)
                throw new IllegalArgumentException("Invalid accepted order");
        }
    }

    private final Map<String, Bid> bids = new LinkedHashMap<>();
    Collection<Bid> values() { return List.copyOf(bids.values()); }
    Set<String> itemIds() {
        Set<String> result = new LinkedHashSet<>();
        for (Bid bid : bids.values()) result.add(bid.itemId());
        return result;
    }
    void clear() { bids.clear(); }

    void remember(Bid bid) {
        Bid before = bids.get(bid.id());
        if (before != null && !before.equals(bid))
            throw new IllegalArgumentException("Accepted order ID reused with different evidence");
        bids.putIfAbsent(bid.id(), bid);
    }

    /** An item-only notice cannot choose a cost when our orders paid different prices. */
    Bid unambiguousItem(String itemId) {
        Bid result = null;
        for (Bid bid : bids.values()) {
            if (!bid.itemId().equals(itemId)) continue;
            if (result != null && result.unitPrice() != bid.unitPrice()) return null;
            result = bid;
        }
        return result;
    }

    Bid matching(String itemId, long unitPrice, int count) {
        Bid result = null;
        for (Bid bid : bids.values()) {
            if (!bid.itemId().equals(itemId) || bid.count() != count
                    || !dev.doughbay.fabric.OwnPrices.shownAs(unitPrice, bid.unitPrice())) continue;
            if (result != null && result.unitPrice() != bid.unitPrice()) return null;
            result = bid;
        }
        return result;
    }

    /** Never erase several receipts because a cancellation supplied only an item and price. */
    boolean cancelUnique(String itemId, long unitPrice, int count, long at) {
        if (at <= 0) throw new IllegalArgumentException("Cancellation needs acceptance time");
        List<Bid> matches = bids.values().stream().filter(b -> b.itemId().equals(itemId)
                && b.cancelledAt() == 0 && b.unitPrice() == unitPrice && (count <= 0 || b.count() == count)).toList();
        if (matches.size() != 1) return false;
        Bid bid=matches.getFirst();
        bids.put(bid.id(),new Bid(bid.id(),bid.itemId(),bid.unitPrice(),bid.count(),bid.placedAt(),at));
        return true;
    }

    void load(Path path) throws IOException {
        Map<String, Bid> read = new LinkedHashMap<>();
        if (Files.exists(path)) {
            int ordinal = 0;
            for (String line : Files.readAllLines(path)) {
                if (line.isBlank()) continue;
                String[] p = line.strip().split("\\|", -1);
                try {
                    if (p.length != 4 && p.length != 5 && p.length != 6) throw new IllegalArgumentException();
                    // Every surviving legacy row is retained. Lost older rows cannot be reconstructed.
                    String id = p.length >= 5 ? p[4] : "legacy-" + UUID.nameUUIDFromBytes(
                            (ordinal + ":" + line).getBytes(StandardCharsets.UTF_8));
                    Bid bid = new Bid(id, p[0], Long.parseLong(p[1]), Integer.parseInt(p[2]), Long.parseLong(p[3]),
                            p.length == 6 ? Long.parseLong(p[5]) : 0);
                    Bid previous = read.putIfAbsent(id, bid);
                    if (previous != null) throw new IllegalArgumentException("Duplicate accepted order ID");
                    ordinal++;
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid accepted order store", e);
                }
            }
        }
        bids.clear();
        bids.putAll(read);
    }

    void save(Path path) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(temporary, bids.values().stream().map(b -> b.itemId() + "|" + b.unitPrice()
                + "|" + b.count() + "|" + b.placedAt() + "|" + b.id() + "|" + b.cancelledAt()).toList());
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}

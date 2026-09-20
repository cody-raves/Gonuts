package dev.doughbay.fabric.automation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/** The resale intent must survive the interval between placing and collecting an order. */
final class RetailOrderPlans {
    private final Map<String, Long> targets = new HashMap<>();
    private Path path;
    private boolean loaded;

    void load(Path path) throws IOException {
        this.path = path;
        loaded = false;
        targets.clear();
        Map<String, Long> read = new HashMap<>();
        if (path != null && Files.exists(path)) {
            for (String line : Files.readAllLines(path)) {
                if (line.isBlank()) continue;
                String[] fields = line.split("\\|");
                try {
                    if (fields.length != 3) throw new IllegalArgumentException();
                    long bid = Long.parseLong(fields[1]), target = Long.parseLong(fields[2]);
                    if (bid <= 0 || target <= 0) throw new IllegalArgumentException();
                    read.put(key(fields[0], bid), target);
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid retail order record", e);
                }
            }
        }
        targets.putAll(read);
        loaded = path != null;
    }

    long target(String item, long unitPrice) { return targets.getOrDefault(key(item, unitPrice), 0L); }

    void remember(String item, long unitPrice, long target) throws IOException {
        if (!loaded || path == null) throw new IOException("Retail order store unavailable");
        if (unitPrice <= 0 || target <= 0) throw new IllegalArgumentException("Invalid retail prices");
        Map<String, Long> next = new HashMap<>(targets);
        next.put(key(item, unitPrice), target);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(temp, next.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "|" + e.getValue()).toList());
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        targets.clear();
        targets.putAll(next);
    }

    private static String key(String item, long price) { return item + "|" + price; }
}

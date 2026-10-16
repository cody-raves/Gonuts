package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Named copies of shareable tuning, shared by a hive without sharing account slot limits. */
public final class SavedPresets {
    public record Entry(String id, String name, String code, long savedAt) { }
    private static volatile SavedPresets current;
    private final Path file;
    private static final ObjectMapper JSON = new ObjectMapper();
    SavedPresets(Path directory) { file = directory.resolve("saved-presets.json"); }
    static void load(Path directory) { current = new SavedPresets(directory); }
    static SavedPresets current() {
        if (current == null) throw new IllegalStateException("Presets are not ready yet");
        return current;
    }
    public synchronized List<Entry> entries() throws IOException {
        if (!Files.exists(file)) return List.of();
        if (Files.size(file) > 4_000_000) throw new IOException("Preset file is too large; existing presets kept");
        var root = JSON.readTree(Files.readAllBytes(file));
        if (root == null || root.path("version").asInt() != 1 || !root.path("presets").isArray())
            throw new IOException("Could not read saved presets; existing file kept");
        List<Entry> result = new ArrayList<>();
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        for (var node : root.path("presets")) {
            var entry = JSON.treeToValue(node, Entry.class);
            validate(entry.name(), entry.code());
            if (!entry.id().matches("[a-f0-9-]{36}") || !ids.add(entry.id()) || !names.add(entry.name().toLowerCase(Locale.ROOT)))
                throw new IOException("Invalid saved presets; existing file kept");
            result.add(entry);
        }
        if (result.size() > 100) throw new IOException("Too many saved presets");
        result.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }
    private static void validate(String name, String code) throws IOException {
        if (name == null || name.isBlank() || name.length() > 48 || name.chars().anyMatch(Character::isISOControl))
            throw new IOException("Use a preset name between 1 and 48 characters");
        if (code == null || !code.startsWith("DB1-") || code.length() > 32_000)
            throw new IOException("Could not capture this settings code");
    }
    public synchronized Entry save(String id, String name, String code) throws IOException {
        name = name == null ? "" : name.strip(); validate(name, code);
        final String cleanName = name;
        return mutate(rows -> {
            Entry previous = id == null || id.isEmpty() ? null : rows.stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow(() -> new IOException("Select an existing preset"));
            if (rows.stream().anyMatch(e -> e.name().equalsIgnoreCase(cleanName) && (previous == null || !e.id().equals(previous.id()))))
                throw new IOException("That name is already used; choose another name");
            if (previous == null && rows.size() >= 100) throw new IOException("You can save up to 100 presets");
            Entry entry = new Entry(previous == null ? UUID.randomUUID().toString() : previous.id(), cleanName, code, System.currentTimeMillis());
            if (previous != null) rows.remove(previous);
            rows.add(entry); return entry;
        });
    }
    public synchronized void delete(String id) throws IOException {
        mutate(rows -> { if (!rows.removeIf(e -> e.id().equals(id))) throw new IOException("Select an existing preset"); return null; });
    }
    public synchronized Entry get(String id) throws IOException {
        return entries().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow(() -> new IOException("Select an existing preset"));
    }
    private interface Edit { Entry apply(List<Entry> rows) throws IOException; }
    private Entry mutate(Edit edit) throws IOException {
        Files.createDirectories(file.getParent());
        try (var channel = FileChannel.open(file.resolveSibling("saved-presets.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock lock;
            try { lock = channel.tryLock(); } catch (java.nio.channels.OverlappingFileLockException busy) { throw new IOException("Presets are being saved by another client; try again"); }
            if (lock == null) throw new IOException("Presets are being saved by another client; try again");
            try (lock) {
                List<Entry> rows = new ArrayList<>(entries()); Entry answer = edit.apply(rows);
                var root = JSON.createObjectNode().put("version", 1); root.set("presets", JSON.valueToTree(rows));
                Path temp = Files.createTempFile(file.getParent(), "saved-presets-", ".tmp");
                try {
                    Files.write(temp, JSON.writeValueAsBytes(root));
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally { Files.deleteIfExists(temp); }
                return answer;
            }
        }
    }
}

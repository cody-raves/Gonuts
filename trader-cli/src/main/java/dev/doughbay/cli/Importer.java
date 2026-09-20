package dev.doughbay.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.doughbay.core.model.ItemFingerprint;
import dev.doughbay.core.model.Sale;
import dev.doughbay.storage.TransactionRepository;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bootstraps price history from a file of completed sales, so analysis does not
 * have to wait for the tool to observe a market for days.
 *
 * <p>This imports data you already have the right to use — your own export, a
 * dataset shared with you, or DoughBay's own archived API pages. It fetches
 * nothing itself.
 *
 * <p>Accepted formats:
 * <ul>
 *   <li>CSV with a header row naming the columns. Recognized names (case
 *       insensitive): sold_at/timestamp/date, seller/seller_name/seller_uuid,
 *       item/item_id, count/amount/quantity, price/total_price.</li>
 *   <li>JSON: either a bare array of records or an object with a
 *       {@code result}/{@code data} array, using the same field names as the
 *       auction API.</li>
 * </ul>
 * Timestamps may be epoch millis, epoch seconds, or ISO-8601.
 */
final class Importer {

    private final TransactionRepository transactions;

    Importer(TransactionRepository transactions) {
        this.transactions = transactions;
    }

    record Report(int read, int imported, int duplicates, int rejected, List<String> problems) {
    }

    Report importFile(Path file, String sourceLabel) throws Exception {
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("No such file: " + file);
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        List<RawRecord> records = content.stripLeading().startsWith("{")
                || content.stripLeading().startsWith("[")
                ? parseJson(content)
                : parseCsv(file);

        int imported = 0, duplicates = 0, rejected = 0;
        List<String> problems = new ArrayList<>();
        for (RawRecord raw : records) {
            try {
                Sale sale = raw.toSale(sourceLabel);
                if (!sale.isValid()) {
                    rejected++;
                    if (problems.size() < 5) problems.add("Invalid record: " + raw);
                    continue;
                }
                if (transactions.insertIfAbsent(sale, raw.rawJson())) {
                    imported++;
                } else {
                    duplicates++;
                }
            } catch (Exception e) {
                rejected++;
                if (problems.size() < 5) problems.add(e.getMessage());
            }
        }
        return new Report(records.size(), imported, duplicates, rejected, problems);
    }

    /**
     * A source row before validation. {@code seller} may be a name rather than
     * a UUID; imported history is still usable for pricing, but seller-based
     * manipulation checks are weaker for it.
     */
    private record RawRecord(String soldAt, String seller, String itemId, String count,
                             String totalPrice, String rawJson) {

        Sale toSale(String sourceLabel) {
            if (itemId == null || itemId.isBlank()) {
                throw new IllegalArgumentException("Missing item id");
            }
            long soldAtMillis = parseTimestamp(soldAt);
            long price = parseLong(totalPrice);
            int itemCount = count == null ? 1 : (int) Math.max(1, parseLong(count));
            ItemFingerprint fingerprint = ItemFingerprint.builder(itemId).build();
            String sellerId = (seller == null || seller.isBlank())
                    ? "imported:" + sourceLabel : seller;
            String hash = Sale.computeHash(soldAtMillis, sellerId,
                    fingerprint.canonicalForm(), itemCount, price);
            return new Sale(hash, soldAtMillis, sellerId, seller == null ? "" : seller,
                    fingerprint.itemKey(), fingerprint.itemId(), itemCount, price);
        }
    }

    private static List<RawRecord> parseJson(String content) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(content);
        JsonNode array = root.isArray() ? root
                : root.has("result") ? root.get("result")
                : root.has("data") ? root.get("data") : null;
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("JSON must be an array, or an object with a result/data array");
        }
        List<RawRecord> out = new ArrayList<>();
        for (JsonNode node : array) {
            JsonNode item = node.has("item") ? node.get("item") : node;
            String itemId = text(item, "id", "item_id", "itemId", "item", "type", "name");
            if (itemId == null) continue;
            String soldAt = text(node, "unixMillisDateSold", "sold_at", "soldAt",
                    "timestamp", "time", "date");
            String price = text(node, "price", "total_price", "totalPrice", "amount", "cost");
            String count = text(item, "count", "amount", "quantity", "qty");
            JsonNode sellerNode = node.get("seller");
            String seller = sellerNode != null && sellerNode.isObject()
                    ? text(sellerNode, "uuid", "id", "name")
                    : text(node, "seller", "seller_name", "sellerName", "seller_uuid");
            out.add(new RawRecord(soldAt, seller, itemId, count, price, node.toString()));
        }
        return out;
    }

    private static List<RawRecord> parseCsv(Path file) throws Exception {
        List<RawRecord> out = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return out;
            String[] headers = splitCsv(headerLine);
            int timeIdx = indexOf(headers, "sold_at", "soldat", "timestamp", "time", "date", "sold");
            int sellerIdx = indexOf(headers, "seller", "seller_name", "sellername", "seller_uuid", "player");
            int itemIdx = indexOf(headers, "item", "item_id", "itemid", "id", "type", "name");
            int countIdx = indexOf(headers, "count", "amount", "quantity", "qty", "stack");
            int priceIdx = indexOf(headers, "price", "total_price", "totalprice", "cost", "value");
            if (itemIdx < 0 || priceIdx < 0 || timeIdx < 0) {
                throw new IllegalArgumentException(
                        "CSV needs at least a timestamp, item and price column. Found: "
                                + String.join(", ", headers));
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] cells = splitCsv(line);
                if (cells.length <= Math.max(itemIdx, priceIdx)) continue;
                out.add(new RawRecord(cell(cells, timeIdx), cell(cells, sellerIdx),
                        cell(cells, itemIdx), countIdx < 0 ? null : cell(cells, countIdx),
                        cell(cells, priceIdx), line));
            }
        }
        return out;
    }

    // --- parsing helpers ----------------------------------------------------

    /** Accepts epoch millis, epoch seconds, or ISO-8601. */
    static long parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing timestamp");
        }
        String trimmed = value.trim();
        try {
            long numeric = Long.parseLong(trimmed);
            // Seconds-vs-millis: anything below this is implausible as millis
            // (it would be 1970), so treat it as seconds.
            return numeric < 100_000_000_000L ? numeric * 1000 : numeric;
        } catch (NumberFormatException ignored) {
            // fall through to ISO parsing
        }
        try {
            return Instant.parse(trimmed).toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(trimmed.replace(' ', 'T') + "Z").toEpochMilli();
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException("Unrecognized timestamp: " + trimmed);
            }
        }
    }

    /** Tolerates currency symbols, thousands separators, and decimals. */
    static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing number");
        }
        String cleaned = value.trim().replaceAll("[,$_\\s]", "");
        if (cleaned.isEmpty()) throw new IllegalArgumentException("Missing number");
        try {
            return (long) Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a number: " + value);
        }
    }

    private static String[] splitCsv(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells.toArray(new String[0]);
    }

    private static int indexOf(String[] headers, String... names) {
        for (int i = 0; i < headers.length; i++) {
            String normalized = headers[i].trim().toLowerCase(Locale.ROOT).replace(" ", "_");
            for (String name : names) {
                if (normalized.equals(name)) return i;
            }
        }
        return -1;
    }

    private static String cell(String[] cells, int index) {
        if (index < 0 || index >= cells.length) return null;
        String value = cells[index].trim();
        return value.isEmpty() ? null : value;
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode child = node.get(name);
            if (child != null && !child.isNull() && child.isValueNode()) {
                return child.asText();
            }
        }
        return null;
    }
}

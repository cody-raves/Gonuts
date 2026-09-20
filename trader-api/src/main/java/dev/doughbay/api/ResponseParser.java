package dev.doughbay.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.doughbay.core.model.CanonicalJson;
import dev.doughbay.core.model.ItemFingerprint;
import dev.doughbay.core.model.ItemTextCanonicalizer;
import dev.doughbay.core.model.Listing;
import dev.doughbay.core.model.NamespacedId;
import dev.doughbay.core.model.Sale;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Defensive parser for auction API payloads. The exact field names may drift,
 * so every accessor tolerates aliases and missing values; records that cannot
 * be normalized are reported as parse failures rather than crashing the run.
 * Raw JSON is preserved alongside each parsed record so parsing can be
 * reproduced later if the API changes.
 */
public final class ResponseParser {

    private static final String[] SOLD_AT_ALIASES = {
            "unixMillisDateSold", "soldAt", "sold_at", "timestamp", "time"
    };
    private static final String[] PRICE_ALIASES = {
            "price", "total_price", "totalPrice", "amount"
    };
    private static final String[] SELLER_CONTAINER_ALIASES = {"seller", "player"};
    private static final String[] SELLER_UUID_ALIASES = {"uuid", "id"};
    private static final String[] SELLER_NAME_ALIASES = {"name", "username"};
    private static final String[] TOP_LEVEL_SELLER_UUID_ALIASES = {
            "seller_uuid", "sellerUuid"
    };
    private static final String[] TOP_LEVEL_SELLER_NAME_ALIASES = {
            "seller_name", "sellerName"
    };
    private static final String[] TIME_LEFT_ALIASES = {
            "time_left", "timeLeft", "unixMillisTimeLeft", "expires_in"
    };
    private static final String[] LISTING_ID_ALIASES = {"id", "listing_id", "listingId"};

    private static final String[] ITEM_ID_ALIASES = {"id", "item_id", "itemId", "type"};
    private static final String[] COUNT_ALIASES = {"count", "amount", "stack_size", "stackSize"};
    private static final String[] NAME_ALIASES = {
            "display_name", "displayName", "name", "custom_name"
    };
    private static final String[] ENCHANTMENT_ALIASES = {"enchants", "enchantments"};
    private static final String[] TRIM_ALIASES = {"trim", "armor_trim", "armorTrim"};
    private static final String[] CONTENT_ALIASES = {
            "contents", "container_contents", "items"
    };

    /**
     * Fields whose item semantics are explicitly normalized below. Everything
     * else inside the item object is identity-bearing unknown metadata. Fields
     * on the surrounding sale/listing object (price, seller, listing id,
     * pagination transport data, and so on) never enter this boundary.
     */
    private static final Set<String> KNOWN_ITEM_FIELDS = Set.of(
            "id", "item_id", "itemId", "type",
            "count", "amount", "stack_size", "stackSize",
            "display_name", "displayName", "name", "custom_name",
            "enchants", "enchantments",
            "lore",
            "trim", "armor_trim", "armorTrim",
            "contents", "container_contents", "items");

    /** Immutable stand-in for "this record carries no enchantments". */
    private static final JsonNode NO_ENCHANTMENTS =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();

    private final ObjectMapper mapper = new ObjectMapper();

    public record ParsedSale(Sale sale, ItemFingerprint fingerprint, String rawJson) {
    }

    public record ParsedListing(Listing listing, ItemFingerprint fingerprint, String rawJson) {
    }

    public record ParseResult<T>(List<T> records, List<String> failures) {
    }

    public JsonNode readTree(String body) throws ApiException {
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new ApiException("Response is not a JSON object");
            }
            return root;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("Malformed JSON response: " + e.getMessage());
        }
    }

    /** The API wraps payloads as {"status": ..., "result": [...]}. */
    public JsonNode extractResultArray(JsonNode root) throws ApiException {
        JsonNode result = firstPresent(root, "result", "results", "data");
        if (result == null || !result.isArray()) {
            throw new ApiException("Response has no result array");
        }
        return result;
    }

    public ParseResult<ParsedSale> parseTransactions(JsonNode resultArray) {
        List<ParsedSale> sales = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (JsonNode node : resultArray) {
            try {
                sales.add(parseTransaction(node));
            } catch (Exception e) {
                failures.add(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        return new ParseResult<>(sales, failures);
    }

    public ParseResult<ParsedListing> parseListings(JsonNode resultArray, long observedAt) {
        List<ParsedListing> listings = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (JsonNode node : resultArray) {
            try {
                listings.add(parseListing(node, observedAt));
            } catch (Exception e) {
                failures.add(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        return new ParseResult<>(listings, failures);
    }

    private ParsedSale parseTransaction(JsonNode node) {
        long soldAt = consistentRequiredLong(
                node, "sold timestamp", true, SOLD_AT_ALIASES);
        long price = consistentRequiredMoney(node, "price", PRICE_ALIASES);
        SellerIdentity seller = consistentSeller(node);
        String sellerUuid = seller.uuid();
        String sellerName = seller.name();

        ItemNormalization item = normalizeItem(requireNode(node, "item", "item"));
        if (price <= 0) throw new IllegalArgumentException("Non-positive price " + price);
        if (sellerUuid == null || sellerUuid.isBlank()) throw new IllegalArgumentException("Missing seller uuid");

        String hash = Sale.computeHash(soldAt, sellerUuid, item.fingerprint().canonicalForm(),
                item.count(), price);
        Sale sale = new Sale(hash, soldAt, sellerUuid,
                sellerName == null ? "" : sellerName,
                item.fingerprint().itemKey(), item.fingerprint().itemId(),
                item.count(), price);
        if (!sale.isValid()) throw new IllegalArgumentException("Invalid transaction record");
        return new ParsedSale(sale, item.fingerprint(), node.toString());
    }

    private ParsedListing parseListing(JsonNode node, long observedAt) {
        long price = consistentRequiredMoney(node, "price", PRICE_ALIASES);
        SellerIdentity seller = consistentSeller(node);
        String sellerUuid = seller.uuid();
        String sellerName = seller.name();

        Long timeLeft = consistentOptionalLong(
                node, "time left", true, TIME_LEFT_ALIASES);
        if (timeLeft != null && timeLeft < 0) {
            throw new IllegalArgumentException("Negative time left " + timeLeft);
        }
        ItemNormalization item = normalizeItem(requireNode(node, "item", "item"));
        if (price <= 0) throw new IllegalArgumentException("Non-positive price " + price);

        // Stable identity for the listing across scans; the API may not expose
        // a listing ID, so derive one from seller + item + price.
        String listingKey = consistentListingId(node);
        if (listingKey == null) {
            listingKey = ItemFingerprint.sha256(
                    sellerUuid + "|" + item.fingerprint().canonicalForm() + "|" + item.count() + "|" + price)
                    .substring(0, 24);
        }

        Listing listing = new Listing(listingKey, observedAt,
                sellerUuid == null ? "" : sellerUuid,
                sellerName == null ? "" : sellerName,
                item.fingerprint().itemKey(), item.fingerprint().itemId(),
                item.count(), price, timeLeft);
        if (!listing.isValid()) throw new IllegalArgumentException("Invalid listing record");
        return new ParsedListing(listing, item.fingerprint(), node.toString());
    }

    private record ItemNormalization(ItemFingerprint fingerprint, int count) {
    }

    private record SellerIdentity(String uuid, String name) {
        private static final SellerIdentity EMPTY = new SellerIdentity("", "");
    }

    private record AliasedValue(String field, JsonNode value) {
    }

    private record NormalizedTrim(String material, String pattern) {
        private static final NormalizedTrim NONE = new NormalizedTrim("", "");
    }

    private static long consistentRequiredLong(JsonNode node, String what,
                                               boolean allowNumericString,
                                               String... aliases) {
        Long value = consistentOptionalLong(node, what, allowNumericString, aliases);
        if (value == null) throw new IllegalArgumentException("Missing " + what);
        return value;
    }

    private static Long consistentOptionalLong(JsonNode node, String what,
                                               boolean allowNumericString,
                                               String... aliases) {
        List<AliasedValue> values = aliasValues(node, aliases);
        if (values.isEmpty()) return null;

        Long selected = null;
        for (AliasedValue alias : values) {
            long parsed = strictIntegralLong(
                    alias.value(), what + " " + alias.field(), allowNumericString);
            if (selected != null && selected != parsed) {
                throw conflictingAliases(what, values);
            }
            selected = parsed;
        }
        return selected;
    }

    /**
     * Reconciles nested seller/player forms with their top-level aliases. A
     * secondary alias may fill a missing value but can never contradict it.
     */
    private static SellerIdentity consistentSeller(JsonNode node) {
        SellerIdentity selected = SellerIdentity.EMPTY;
        for (AliasedValue container : aliasValues(node, SELLER_CONTAINER_ALIASES)) {
            if (!container.value().isObject()) {
                throw invalidAliasShape(container, "a seller object");
            }
            String uuid = consistentOptionalSellerUuid(
                    container.value(), SELLER_UUID_ALIASES);
            String name = consistentOptionalSellerName(
                    container.value(), SELLER_NAME_ALIASES);
            selected = mergeSeller(selected, new SellerIdentity(uuid, name));
        }

        String topLevelUuid = consistentOptionalSellerUuid(
                node, TOP_LEVEL_SELLER_UUID_ALIASES);
        String topLevelName = consistentOptionalSellerName(
                node, TOP_LEVEL_SELLER_NAME_ALIASES);
        return mergeSeller(selected, new SellerIdentity(topLevelUuid, topLevelName));
    }

    private static String consistentOptionalSellerUuid(JsonNode node, String... aliases) {
        List<AliasedValue> values = aliasValues(node, aliases);
        String selected = "";
        for (AliasedValue alias : values) {
            if (!alias.value().isTextual()) {
                throw invalidAliasShape(alias, "a textual seller id");
            }
            String normalized = normalizeSellerUuid(alias.value().textValue());
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("Seller id cannot be blank");
            }
            if (!selected.isEmpty() && !selected.equals(normalized)) {
                throw conflictingAliases("seller id", values);
            }
            selected = normalized;
        }
        return selected;
    }

    private static String consistentOptionalSellerName(JsonNode node, String... aliases) {
        List<AliasedValue> values = aliasValues(node, aliases);
        String selected = "";
        for (AliasedValue alias : values) {
            if (!alias.value().isTextual()) {
                throw invalidAliasShape(alias, "a textual seller name");
            }
            String normalized = alias.value().textValue().strip();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("Seller name cannot be blank");
            }
            if (!selected.isEmpty() && !selected.equalsIgnoreCase(normalized)) {
                throw conflictingAliases("seller name", values);
            }
            if (selected.isEmpty()) selected = normalized;
        }
        return selected;
    }

    private static SellerIdentity mergeSeller(SellerIdentity selected,
                                              SellerIdentity candidate) {
        String uuid = selected.uuid();
        if (!candidate.uuid().isEmpty()) {
            if (!uuid.isEmpty() && !uuid.equals(candidate.uuid())) {
                throw new IllegalArgumentException("Conflicting nested/top-level seller id aliases");
            }
            uuid = candidate.uuid();
        }

        String name = selected.name();
        if (!candidate.name().isEmpty()) {
            if (!name.isEmpty() && !name.equalsIgnoreCase(candidate.name())) {
                throw new IllegalArgumentException("Conflicting nested/top-level seller name aliases");
            }
            if (name.isEmpty()) name = candidate.name();
        }
        return new SellerIdentity(uuid, name);
    }

    private static String normalizeSellerUuid(String raw) {
        String normalized = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        String compact = normalized.replace("-", "");
        return compact.matches("[0-9a-f]{32}") ? compact : normalized;
    }

    private static String consistentListingId(JsonNode node) {
        List<AliasedValue> values = aliasValues(node, LISTING_ID_ALIASES);
        if (values.isEmpty()) return null;

        String selected = null;
        for (AliasedValue alias : values) {
            JsonNode value = alias.value();
            final String normalized;
            if (value.isTextual()) {
                normalized = value.textValue().strip();
            } else if (value.isIntegralNumber()) {
                // Some APIs expose a numeric auction id. Its decimal spelling
                // is a stable scalar identity; fractions and booleans are not.
                normalized = value.bigIntegerValue().toString();
            } else {
                throw invalidAliasShape(alias, "a textual or integral listing id");
            }
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("Listing id cannot be blank");
            }
            if (selected != null && !selected.equals(normalized)) {
                throw conflictingAliases("listing id", values);
            }
            selected = normalized;
        }
        return selected;
    }

    private ItemNormalization normalizeItem(JsonNode item) {
        if (item == null || !item.isObject()) {
            throw new IllegalArgumentException("Item must be a JSON object");
        }
        String id = consistentItemId(item);
        int count = consistentItemCount(item);

        ItemFingerprint.Builder fp = ItemFingerprint.builder(id);
        String name = consistentDisplayName(item, id);
        if (!name.isEmpty()) {
            fp.displayName(name);
        }

        SortedMap<String, Integer> enchantments = consistentEnchantments(item);
        for (Map.Entry<String, Integer> enchantment : enchantments.entrySet()) {
            fp.enchantment(enchantment.getKey(), enchantment.getValue());
        }

        String lore = consistentLore(item);
        if (!lore.isEmpty()) {
            fp.lore(lore);
        }

        NormalizedTrim trim = consistentTrim(item);
        if (!trim.equals(NormalizedTrim.NONE)) {
            fp.trim(trim.material(), trim.pattern());
        }

        String contents = consistentContents(item);
        if (!contents.isEmpty()) {
            fp.containerContentsJson(contents);
        }

        ObjectNode unknownMetadata = mapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = item.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!KNOWN_ITEM_FIELDS.contains(field.getKey())) {
                // Preserve the full structural value. Builder canonicalization
                // sorts object keys and normalizes numbers before hashing, so
                // harmless JSON field-order changes retain the same identity.
                unknownMetadata.set(field.getKey(), field.getValue().deepCopy());
            }
        }
        if (!unknownMetadata.isEmpty()) {
            fp.extraMetadataJson(unknownMetadata.toString());
        }

        return new ItemNormalization(fp.build(), count);
    }

    /** Every supplied id alias must be textual and normalize to one identity. */
    private static String consistentItemId(JsonNode item) {
        List<AliasedValue> values = aliasValues(item, ITEM_ID_ALIASES);
        if (values.isEmpty()) throw new IllegalArgumentException("Item has no id");

        String selected = null;
        for (AliasedValue alias : values) {
            if (!alias.value().isTextual()) {
                throw invalidAliasShape(alias, "a textual item id");
            }
            String normalized = NamespacedId.normalize(alias.value().textValue());
            if (selected != null && !selected.equals(normalized)) {
                throw conflictingAliases("item id", values);
            }
            selected = normalized;
        }
        return selected;
    }

    /**
     * Counts intentionally retain numeric-string compatibility, but never
     * coerce objects, fractions, overflow, or contradictory aliases to one.
     */
    private static int consistentItemCount(JsonNode item) {
        List<AliasedValue> values = aliasValues(item, COUNT_ALIASES);
        if (values.isEmpty()) return 1;

        Integer selected = null;
        for (AliasedValue alias : values) {
            long parsed = strictIntegralLong(alias.value(), "item count " + alias.field(), true);
            if (parsed <= 0 || parsed > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Item count " + parsed + " outside 1.." + Integer.MAX_VALUE);
            }
            int normalized = (int) parsed;
            if (selected != null && selected != normalized) {
                throw conflictingAliases("item count", values);
            }
            selected = normalized;
        }
        return selected;
    }

    /** Normalizes text/JSON components and rejects contradictory name aliases. */
    private static String consistentDisplayName(JsonNode item, String itemId) {
        List<AliasedValue> values = aliasValues(item, NAME_ALIASES);
        if (values.isEmpty()) return "";

        String selected = null;
        String plainName = ItemTextCanonicalizer.displayName(bareName(itemId));
        for (AliasedValue alias : values) {
            JsonNode value = alias.value();
            final String raw;
            if (value.isTextual()) {
                raw = value.textValue();
            } else if (value.isObject() || value.isArray()) {
                // JSON text components are identity-bearing and canonicalized
                // structurally. Other scalar types are never plausible names.
                raw = CanonicalJson.canonicalize(value.toString());
            } else {
                throw invalidAliasShape(alias, "text or a JSON text component");
            }
            String normalized = ItemTextCanonicalizer.displayName(raw);
            if (normalized.equalsIgnoreCase(plainName)) normalized = "";
            if (selected != null && !selected.equals(normalized)) {
                throw conflictingAliases("display name", values);
            }
            selected = normalized;
        }
        return selected == null ? "" : selected;
    }

    /**
     * The id&rarr;level map inside one {@code enchants} alias value.
     *
     * <p>The auction API wraps this twice: {@code enchants} is an ItemData
     * object holding {@code enchantments}, which in turn holds {@code levels},
     * and it carries the armor trim as a sibling. Reading the alias value
     * directly therefore walks into {@code enchantments} and {@code trim} as
     * though they were enchantment ids. Simpler payloads put the pairs
     * straight on the alias value, so both shapes unwrap to the same map
     * rather than one being preferred.
     */
    private static JsonNode enchantmentLevels(AliasedValue alias) {
        JsonNode value = alias.value();
        // Only the documented wrapper has these keys; an enchantment id is
        // always namespaced, so neither can collide with a real entry.
        if (!value.has("enchantments") && !value.has("levels") && !value.has("trim")) {
            return value;
        }
        JsonNode wrapper = value.get("enchantments");
        if (wrapper != null && !wrapper.isNull()) {
            if (!wrapper.isObject()) throw invalidAliasShape(alias, "an enchantment object");
            value = wrapper;
        }
        JsonNode levels = value.get("levels");
        if (levels == null || levels.isNull()) {
            // A wrapper carrying only a trim means no enchantments, not a
            // malformed record.
            return NO_ENCHANTMENTS;
        }
        if (!levels.isObject()) throw invalidAliasShape(alias, "an enchantment level object");
        return levels;
    }

    /**
     * Every place an armor trim can appear: on the item itself, and nested
     * beside the enchantments in the API's ItemData wrapper. Both feed the
     * same consistency check, so a record carrying two disagreeing trims is
     * still rejected rather than silently resolved.
     */
    private static List<AliasedValue> trimAliasValues(JsonNode item) {
        List<AliasedValue> values = new ArrayList<>(aliasValues(item, TRIM_ALIASES));
        for (AliasedValue enchants : aliasValues(item, ENCHANTMENT_ALIASES)) {
            if (!enchants.value().isObject()) continue;
            JsonNode nested = enchants.value().get("trim");
            if (nested != null && !nested.isNull()) {
                values.add(new AliasedValue(enchants.field() + ".trim", nested));
            }
        }
        return List.copyOf(values);
    }

    private static SortedMap<String, Integer> consistentEnchantments(JsonNode item) {
        List<AliasedValue> values = aliasValues(item, ENCHANTMENT_ALIASES);
        if (values.isEmpty()) return Collections.unmodifiableSortedMap(new TreeMap<>());

        SortedMap<String, Integer> selected = null;
        for (AliasedValue alias : values) {
            if (!alias.value().isObject()) {
                throw invalidAliasShape(alias, "an enchantment object");
            }
            SortedMap<String, Integer> normalized = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = enchantmentLevels(alias).fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String enchantmentId = NamespacedId.normalize(field.getKey());
                long level = strictIntegralLong(field.getValue(),
                        "enchantment level " + field.getKey(), true);
                if (level < 1 || level > ItemFingerprint.MAX_ENCHANTMENT_LEVEL) {
                    throw new IllegalArgumentException("Enchantment level " + level
                            + " outside 1.." + ItemFingerprint.MAX_ENCHANTMENT_LEVEL);
                }
                Integer prior = normalized.putIfAbsent(enchantmentId, (int) level);
                if (prior != null && prior != (int) level) {
                    throw new IllegalArgumentException(
                            "Conflicting normalized enchantment " + enchantmentId);
                }
            }
            if (selected != null && !selected.equals(normalized)) {
                throw conflictingAliases("enchantments", values);
            }
            selected = normalized;
        }
        return Collections.unmodifiableSortedMap(
                selected == null ? new TreeMap<>() : new TreeMap<>(selected));
    }

    private static String consistentLore(JsonNode item) {
        List<AliasedValue> values = aliasValues(item, "lore");
        if (values.isEmpty()) return "";

        String selected = null;
        for (AliasedValue alias : values) {
            if (!alias.value().isArray()) {
                throw invalidAliasShape(alias, "a lore array");
            }
            StringBuilder normalized = new StringBuilder();
            for (JsonNode line : alias.value()) {
                if (line.isTextual()) {
                    normalized.append(line.textValue());
                } else if (line.isObject() || line.isArray()) {
                    normalized.append(CanonicalJson.canonicalize(line.toString()));
                } else {
                    throw new IllegalArgumentException(
                            "Lore lines must be text or JSON text components");
                }
                normalized.append('\n');
            }
            String canonical = ItemTextCanonicalizer.lore(normalized.toString());
            if (selected != null && !selected.equals(canonical)) {
                throw conflictingAliases("lore", values);
            }
            selected = canonical;
        }
        return selected == null ? "" : selected;
    }

    private static NormalizedTrim consistentTrim(JsonNode item) {
        List<AliasedValue> values = trimAliasValues(item);
        if (values.isEmpty()) return NormalizedTrim.NONE;

        NormalizedTrim selected = null;
        for (AliasedValue alias : values) {
            JsonNode value = alias.value();
            if (!value.isObject()) {
                throw invalidAliasShape(alias, "a trim object");
            }
            Iterator<String> fieldNames = value.fieldNames();
            while (fieldNames.hasNext()) {
                String field = fieldNames.next();
                if (!field.equals("material") && !field.equals("pattern")) {
                    throw new IllegalArgumentException("Unknown trim field " + field);
                }
            }
            String material = strictOptionalText(value, "trim material", "material");
            String pattern = strictOptionalText(value, "trim pattern", "pattern");
            boolean materialMissing = material == null || material.isBlank();
            boolean patternMissing = pattern == null || pattern.isBlank();
            if (materialMissing != patternMissing) {
                throw new IllegalArgumentException(
                        "Trim material and pattern must both be present");
            }
            NormalizedTrim normalized = materialMissing
                    ? NormalizedTrim.NONE
                    : new NormalizedTrim(
                    NamespacedId.normalize(material), NamespacedId.normalize(pattern));
            if (selected != null && !selected.equals(normalized)) {
                throw conflictingAliases("armor trim", values);
            }
            selected = normalized;
        }
        return selected == null ? NormalizedTrim.NONE : selected;
    }

    private static String consistentContents(JsonNode item) {
        List<AliasedValue> values = aliasValues(item, CONTENT_ALIASES);
        if (values.isEmpty()) return "";

        String selected = null;
        for (AliasedValue alias : values) {
            JsonNode value = alias.value();
            if (!value.isArray() && !value.isObject()) {
                throw invalidAliasShape(alias, "a container array or object");
            }
            String normalized = value.isEmpty()
                    ? ""
                    : CanonicalJson.canonicalize(value.toString());
            if (selected != null && !selected.equals(normalized)) {
                throw conflictingAliases("container contents", values);
            }
            selected = normalized;
        }
        return selected == null ? "" : selected;
    }

    private static List<AliasedValue> aliasValues(JsonNode node, String... names) {
        List<AliasedValue> values = new ArrayList<>();
        for (String name : names) {
            JsonNode value = node.get(name);
            // Explicit null has the same semantics as an absent optional API
            // field, but cannot hide a populated secondary alias.
            if (value != null && !value.isNull()) {
                values.add(new AliasedValue(name, value));
            }
        }
        return List.copyOf(values);
    }

    /**
     * A monetary amount, rounded to the nearest whole coin.
     *
     * <p>{@code price} is the only {@code number} in the auction API; every
     * other numeric field is an {@code integer}, and the one sibling that
     * spells out its format calls it {@code float64}. The economy is therefore
     * decimal, and a price carrying a fractional part is ordinary data rather
     * than a malformed record.
     *
     * <p>Rounding rather than rejecting is safe here and only here: sub-coin
     * precision is many orders of magnitude below any tradable edge, and the
     * alternative silently discards real sales from valuation. Counts,
     * enchantment levels, and timestamps keep {@link #strictIntegralLong},
     * where a fraction really does mean the record is wrong.
     */
    private static long strictMoneyLong(JsonNode value, String what) {
        java.math.BigDecimal amount = moneyAmount(value);
        if (amount == null) {
            throw new IllegalArgumentException("Non-numeric " + what
                    + " (" + describeValue(value) + ")");
        }
        try {
            return amount.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(what
                    + " is outside the supported range (" + describeValue(value) + ")");
        }
    }

    /** Numbers and numeric strings, in any spelling; nothing else. */
    private static java.math.BigDecimal moneyAmount(JsonNode value) {
        if (value == null) return null;
        if (value.isNumber()) return value.decimalValue();
        if (value.isTextual()) {
            try {
                return new java.math.BigDecimal(value.textValue().strip());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** Money variant of {@link #consistentRequiredLong}: aliases must agree. */
    private static long consistentRequiredMoney(JsonNode node, String what,
                                                String... aliases) {
        List<AliasedValue> values = aliasValues(node, aliases);
        if (values.isEmpty()) throw new IllegalArgumentException("Missing " + what);

        Long selected = null;
        for (AliasedValue alias : values) {
            long parsed = strictMoneyLong(alias.value(), what + " " + alias.field());
            if (selected != null && selected != parsed) {
                throw conflictingAliases(what, values);
            }
            selected = parsed;
        }
        return selected;
    }

    private static long strictIntegralLong(JsonNode value, String what,
                                           boolean allowNumericString) {
        if (value != null && value.isIntegralNumber() && value.canConvertToLong()) {
            return value.longValue();
        }
        // The auction API documents `price` as `number`, not `integer`, and
        // serializes it from a float. Go's JSON encoder spells a large float64
        // in scientific notation ("1.5e+09"), which Jackson reads as a double
        // rather than an integral node — so a whole-numbered price above the
        // notation threshold would otherwise fail every single row. A value
        // that is exactly whole is a whole number regardless of how the token
        // was spelled; a genuinely fractional one still fails below.
        //
        // The comparison runs on the double's exact decimal expansion rather
        // than on the double itself. Auction prices here run past 2^53, where
        // doubles can no longer name consecutive integers, but that precision
        // was already spent by the API's own float64 — refusing the value
        // would reject real listings, so take faithfully what was sent.
        if (value != null && value.isFloatingPointNumber()) {
            try {
                return value.decimalValue().stripTrailingZeros().longValueExact();
            } catch (ArithmeticException ignored) {
                // Genuinely fractional, or wider than a long. Report below.
            }
        }
        if (allowNumericString && value != null && value.isTextual()) {
            try {
                return Long.parseLong(value.textValue().trim());
            } catch (NumberFormatException ignored) {
                // Report one stable boundary error below.
            }
        }
        // The value itself is the diagnostic. "Non-integral price" says a row
        // was dropped; "Non-integral price (1.2E+19)" says why, and whether the
        // rows being lost are systematically the expensive ones.
        throw new IllegalArgumentException("Non-integral " + what
                + " (" + describeValue(value) + ")");
    }

    /** A short, printable rendering of an offending JSON token. */
    private static String describeValue(JsonNode value) {
        if (value == null) return "absent";
        String text = value.toString();
        return text.length() <= 40 ? text : text.substring(0, 40) + "...";
    }

    private static String strictOptionalText(JsonNode node, String what, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) {
            throw new IllegalArgumentException(what + " must be textual");
        }
        return value.textValue();
    }

    private static IllegalArgumentException invalidAliasShape(
            AliasedValue alias, String expected) {
        return new IllegalArgumentException(
                "Item field " + alias.field() + " must be " + expected);
    }

    private static IllegalArgumentException conflictingAliases(
            String what, List<AliasedValue> values) {
        String fields = values.stream().map(AliasedValue::field)
                .reduce((left, right) -> left + ", " + right).orElse("unknown");
        return new IllegalArgumentException(
                "Conflicting " + what + " aliases: " + fields);
    }

    private static String bareName(String id) {
        String s = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return s.replace('_', ' ');
    }

    // --- JsonNode helpers ---------------------------------------------------

    private static JsonNode firstPresent(JsonNode node, String... names) {
        for (String n : names) {
            JsonNode child = node.get(n);
            if (child != null && !child.isNull()) return child;
        }
        return null;
    }

    private static JsonNode requireNode(JsonNode node, String what, String... names) {
        JsonNode child = firstPresent(node, names);
        if (child == null) throw new IllegalArgumentException("Missing " + what);
        return child;
    }

}

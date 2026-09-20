package dev.doughbay.core.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Identifies an exact market. Two items with the same Minecraft ID but different
 * enchantments, names, lore, trims, or container contents are different markets
 * and must never share a fingerprint.
 *
 * <p>The stack count is deliberately NOT part of the fingerprint: stats are
 * grouped by (item_key, stack_bucket) so that x16/x32/x64 stacks of the same
 * item can be analyzed as related but distinct sub-markets.
 */
public final class ItemFingerprint {

    public static final int MAX_ENCHANTMENT_LEVEL = 255;

    private final String itemId;
    private final String displayName;         // normalized; "" when unnamed
    private final SortedMap<String, Integer> enchantments;
    private final String trimMaterial;        // "" when none
    private final String trimPattern;         // "" when none
    private final String loreHash;            // "" when no lore
    private final String containerHash;       // "" when not a filled container
    private final String extraMetadataHash;   // "" when no additional API metadata

    private ItemFingerprint(Builder b) {
        this.itemId = b.itemId;
        this.displayName = b.displayName;
        this.enchantments = Collections.unmodifiableSortedMap(new TreeMap<>(b.enchantments));
        this.trimMaterial = b.trimMaterial;
        this.trimPattern = b.trimPattern;
        this.loreHash = b.loreHash;
        this.containerHash = b.containerHash;
        this.extraMetadataHash = b.extraMetadataHash;
    }

    public String itemId() { return itemId; }
    public String displayName() { return displayName; }
    public SortedMap<String, Integer> enchantments() { return enchantments; }
    public String trimMaterial() { return trimMaterial; }
    public String trimPattern() { return trimPattern; }
    public String loreHash() { return loreHash; }
    public String containerHash() { return containerHash; }
    public String extraMetadataHash() { return extraMetadataHash; }

    /** True when the item carries no metadata that could split the market. */
    public boolean isPlain() {
        return displayName.isEmpty()
                && enchantments.isEmpty()
                && trimMaterial.isEmpty()
                && trimPattern.isEmpty()
                && loreHash.isEmpty()
                && containerHash.isEmpty()
                && extraMetadataHash.isEmpty();
    }

    /**
     * Canonical, order-independent textual form. Stable across restarts and
     * across enchantment/JSON key ordering differences in API responses.
     */
    public String canonicalForm() {
        StringBuilder sb = new StringBuilder(itemId);
        sb.append("|name=").append(displayName);
        sb.append("|ench=");
        boolean first = true;
        for (Map.Entry<String, Integer> e : enchantments.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        sb.append("|trim=").append(trimMaterial).append('/').append(trimPattern);
        sb.append("|lore=").append(loreHash);
        sb.append("|container=").append(containerHash);
        sb.append("|extra=").append(extraMetadataHash);
        return sb.toString();
    }

    /** Compact key used as item_key in storage: itemId plus a hash of the metadata. */
    public String itemKey() {
        if (isPlain()) {
            return itemId;
        }
        return itemId + "#" + sha256(canonicalForm()).substring(0, 16);
    }

    /** Full SHA-256 exact identity for nested/container canonicalization. */
    public String identityHash() {
        return sha256(canonicalForm());
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static Builder builder(String itemId) {
        return new Builder(itemId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ItemFingerprint that)) return false;
        return canonicalForm().equals(that.canonicalForm());
    }

    @Override
    public int hashCode() {
        return Objects.hash(canonicalForm());
    }

    @Override
    public String toString() {
        return canonicalForm();
    }

    public static final class Builder {
        private final String itemId;
        private String displayName = "";
        private final SortedMap<String, Integer> enchantments = new TreeMap<>();
        private String trimMaterial = "";
        private String trimPattern = "";
        private String loreHash = "";
        private String containerHash = "";
        private String extraMetadataHash = "";

        private Builder(String itemId) {
            if (itemId == null || itemId.isBlank()) {
                throw new IllegalArgumentException("itemId is required");
            }
            this.itemId = NamespacedId.normalize(itemId);
        }

        public Builder displayName(String name) {
            // A display name identical to nothing (null/blank) is an unnamed item.
            this.displayName = ItemTextCanonicalizer.displayName(name);
            return this;
        }

        public Builder enchantment(String id, int level) {
            if (level < 1 || level > MAX_ENCHANTMENT_LEVEL) {
                throw new IllegalArgumentException("enchantment level must be between 1 and "
                        + MAX_ENCHANTMENT_LEVEL + ": " + level);
            }
            String normalizedId = NamespacedId.normalize(id);
            Integer previous = enchantments.putIfAbsent(normalizedId, level);
            if (previous != null && previous != level) {
                throw new IllegalArgumentException("conflicting levels for enchantment "
                        + normalizedId + ": " + previous + " and " + level);
            }
            return this;
        }

        public Builder trim(String material, String pattern) {
            boolean materialMissing = material == null || material.isBlank();
            boolean patternMissing = pattern == null || pattern.isBlank();
            if (materialMissing != patternMissing) {
                throw new IllegalArgumentException("trim material and pattern must both be present");
            }
            this.trimMaterial = materialMissing ? "" : NamespacedId.normalize(material);
            this.trimPattern = patternMissing ? "" : NamespacedId.normalize(pattern);
            return this;
        }

        public Builder lore(String loreText) {
            String canonical = ItemTextCanonicalizer.lore(loreText);
            this.loreHash = canonical.isEmpty() ? "" : sha256(canonical);
            return this;
        }

        public Builder containerContents(String canonicalContents) {
            this.containerHash = hashStructuredValue(canonicalContents);
            return this;
        }

        /** Uses the capped, recursively canonical container representation. */
        public Builder containerContents(ContainerContents contents) {
            this.containerHash = contents == null || contents.entries().isEmpty()
                    ? "" : contents.sha256();
            return this;
        }

        /** Requires valid JSON and hashes its canonical structural form. */
        public Builder containerContentsJson(String json) {
            this.containerHash = json == null || json.isBlank()
                    ? "" : sha256(CanonicalJson.canonicalize(json));
            return this;
        }

        public Builder extraMetadata(String canonicalMetadata) {
            this.extraMetadataHash = hashStructuredValue(canonicalMetadata);
            return this;
        }

        /** Requires valid JSON and hashes its canonical structural form. */
        public Builder extraMetadataJson(String json) {
            this.extraMetadataHash = json == null || json.isBlank()
                    ? "" : sha256(CanonicalJson.canonicalize(json));
            return this;
        }

        private static String hashStructuredValue(String value) {
            if (value == null || value.isBlank()) return "";
            String stripped = value.strip();
            if ((stripped.startsWith("{") && stripped.endsWith("}"))
                    || (stripped.startsWith("[") && stripped.endsWith("]"))) {
                stripped = CanonicalJson.canonicalize(stripped);
            }
            return sha256(stripped);
        }

        public ItemFingerprint build() {
            return new ItemFingerprint(this);
        }
    }
}

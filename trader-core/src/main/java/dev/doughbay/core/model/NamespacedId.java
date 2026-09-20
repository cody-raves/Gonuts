package dev.doughbay.core.model;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validation and canonicalization for Minecraft resource locations.
 *
 * <p>The API is deliberately strict after harmless outer whitespace and case
 * normalization. Accepting spaces, extra colons, or other punctuation would
 * allow malformed API records to collapse into apparently valid markets.</p>
 */
public final class NamespacedId {

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9/._-]+");

    private NamespacedId() {
    }

    /**
     * Returns the canonical {@code namespace:path} representation. A missing
     * namespace is interpreted as vanilla Minecraft for compatibility with
     * API payloads which return values such as {@code ender_pearl}.
     */
    public static String normalize(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            throw new IllegalArgumentException("resource id is required");
        }

        String value = rawId.strip().toLowerCase(Locale.ROOT);
        int firstColon = value.indexOf(':');
        if (firstColon < 0) {
            value = "minecraft:" + value;
            firstColon = "minecraft".length();
        }
        if (firstColon == 0 || firstColon == value.length() - 1
                || firstColon != value.lastIndexOf(':')) {
            throw invalid(rawId);
        }

        String namespace = value.substring(0, firstColon);
        String path = value.substring(firstColon + 1);
        if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches()) {
            throw invalid(rawId);
        }
        return namespace + ':' + path;
    }

    public static boolean isValid(String rawId) {
        try {
            normalize(rawId);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static IllegalArgumentException invalid(String rawId) {
        return new IllegalArgumentException("invalid resource id: " + rawId);
    }
}

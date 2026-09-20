package dev.doughbay.core.model;

import java.text.Normalizer;

/** Canonicalizes item names and lore without depending on Minecraft classes. */
public final class ItemTextCanonicalizer {

    private ItemTextCanonicalizer() {
    }

    /**
     * Canonicalizes a display-name value while preserving visible text and
     * formatting. JSON text components are structurally canonicalized so key
     * order and insignificant JSON whitespace cannot split a market.
     */
    public static String displayName(String raw) {
        return canonicalText(raw, false);
    }

    /**
     * Canonicalizes lore line endings and JSON text components. Line order is
     * significant, as it is visible item metadata.
     */
    public static String lore(String raw) {
        return canonicalText(raw, true);
    }

    private static String canonicalText(String raw, boolean multiline) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();
        if (normalized.isEmpty()) {
            return "";
        }

        StringBuilder formattingNormalized = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char current = normalized.charAt(i);
            formattingNormalized.append(current);
            if (current == '\u00a7' && i + 1 < normalized.length()) {
                formattingNormalized.append(Character.toLowerCase(normalized.charAt(++i)));
            }
        }
        normalized = formattingNormalized.toString();

        if (looksLikeJson(normalized)) {
            try {
                return "json:" + CanonicalJson.canonicalize(normalized);
            } catch (IllegalArgumentException ignored) {
                // A name beginning with '{' is still legal plain text. Keep it
                // verbatim rather than guessing that malformed JSON is a text
                // component. Raw metadata validation belongs at the API edge.
            }
        }

        if (!multiline && normalized.indexOf('\n') >= 0) {
            // Preserve the distinction rather than silently deleting content.
            return normalized.replace("\n", "\\n");
        }
        return normalized;
    }

    private static boolean looksLikeJson(String value) {
        return (value.startsWith("{") && value.endsWith("}"))
                || (value.startsWith("[") && value.endsWith("]"));
    }
}

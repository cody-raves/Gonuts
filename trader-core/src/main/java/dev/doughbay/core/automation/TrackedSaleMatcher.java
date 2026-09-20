package dev.doughbay.core.automation;

import dev.doughbay.core.model.NamespacedId;
import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;
import dev.doughbay.core.text.DonutSaleMessageParser;

import java.util.Locale;
import java.util.regex.Pattern;

/** Strictly matches one trusted server sale notice to one tracked position. */
public final class TrackedSaleMatcher {
    private static final Pattern PLAYER_NAME = Pattern.compile("\\.?[A-Za-z0-9_]{1,16}");

    /**
     * Resolves an item key to the name the server prints in sale notices
     * ("Bottle o' Enchanting", "Block of Iron"), or "" when unknown. The
     * id-derived name ("experience bottle") is always accepted as well.
     */
    private final java.util.function.Function<String, String> displayNames;

    public TrackedSaleMatcher() {
        this(key -> "");
    }

    public TrackedSaleMatcher(java.util.function.Function<String, String> displayNames) {
        this.displayNames = displayNames == null ? key -> "" : displayNames;
    }

    public MatchResult match(Position position, DonutSaleMessageParser.SaleNotice notice) {
        if (position == null || notice == null) return MatchResult.no("Missing position or notice");
        if (position.status() != PositionStatus.LISTED) {
            return MatchResult.no("Position is not in LISTED state");
        }
        if (!PLAYER_NAME.matcher(notice.buyer()).matches()) {
            return MatchResult.no("Buyer identity is malformed");
        }
        if (position.quantity() <= 0 || position.targetPrice() <= 0) {
            return MatchResult.no("Tracked quantity or target price is invalid");
        }
        // The server prints the price abbreviated ("$12.2K" for 12,225), so
        // the listing price must fall inside the band the text can mean.
        if (!notice.couldBe(position.targetPrice())) {
            return MatchResult.no("Sale price does not cover the tracked listing price");
        }
        // The observed notice names no count even for a full stack
        // ("bought your Stone Bricks for $9K" for 64); item and price decide.
        if (notice.count().isPresent()
                && notice.count().getAsInt() != position.quantity()) {
            return MatchResult.no("Sale quantity does not equal the tracked stack");
        }

        String expected = displayName(position.itemKey());
        String observed = normalizeItemText(notice.itemText());
        String clientName = "";
        try {
            clientName = normalizeItemText(displayNames.apply(position.itemKey()));
        } catch (RuntimeException ignored) {
            // A resolver failure only removes the alias, never the match.
        }
        boolean matches = (!expected.isEmpty() && expected.equals(observed))
                || (!clientName.isEmpty() && clientName.equals(observed));
        if (!matches) {
            return MatchResult.no("Sale item text does not exactly match the tracked item");
        }
        return new MatchResult(true, "Exact item, quantity, and price matched");
    }

    private static String displayName(String itemKey) {
        if (itemKey == null || itemKey.isBlank() || itemKey.indexOf('#') >= 0) return "";
        final String id;
        try {
            id = NamespacedId.normalize(itemKey);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
        if (!id.equals(itemKey)) return "";
        return normalizeItemText(id.substring(id.indexOf(':') + 1).replace('_', ' '));
    }

    private static String normalizeItemText(String text) {
        if (text == null) return "";
        return text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    public record MatchResult(boolean matched, String detail) {
        public MatchResult {
            detail = detail == null ? "" : detail;
        }

        private static MatchResult no(String detail) {
            return new MatchResult(false, detail);
        }
    }
}

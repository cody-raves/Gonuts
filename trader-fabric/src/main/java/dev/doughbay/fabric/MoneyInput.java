package dev.doughbay.fabric;

import java.util.Locale;

/**
 * Money typed the way players say it: "5m", "750k", "1.5b", "$75,000,000".
 *
 * <p>Kept apart from the screen so it can be tested without loading any game
 * class. Anything that isn't a clean positive amount is rejected rather than
 * guessed at, because these numbers become spend limits.
 */
final class MoneyInput {

    private MoneyInput() {
    }

    /** The amount typed, or -1 if it isn't one. */
    static long parse(String text) {
        if (text == null) return -1;
        String s = text.strip().toLowerCase(Locale.ROOT)
                .replace(",", "").replace("$", "").replace("_", "").strip();
        if (s.isEmpty()) return -1;
        double multiplier = 1;
        char suffix = s.charAt(s.length() - 1);
        if (suffix == 'k') multiplier = 1e3;
        else if (suffix == 'm') multiplier = 1e6;
        else if (suffix == 'b') multiplier = 1e9;
        if (multiplier != 1) s = s.substring(0, s.length() - 1).strip();
        if (s.isEmpty() || !s.matches("[0-9]*\\.?[0-9]+")) return -1;
        double value = Double.parseDouble(s) * multiplier;
        if (!Double.isFinite(value) || value < 1 || value > 1e15) return -1;
        return Math.round(value);
    }

    /** 5_000_000 to "5m", 750_000 to "750k"; anything uneven stays a plain number. */
    static String compact(long value) {
        if (value >= 1_000_000_000L && value % 1_000_000_000L == 0) return (value / 1_000_000_000L) + "b";
        if (value >= 1_000_000L && value % 1_000_000L == 0) return (value / 1_000_000L) + "m";
        if (value >= 1_000L && value % 1_000L == 0) return (value / 1_000L) + "k";
        return Long.toString(value);
    }
}

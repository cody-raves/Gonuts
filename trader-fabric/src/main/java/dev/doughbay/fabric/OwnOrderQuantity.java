package dev.doughbay.fabric;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/** Exact integer evidence only. A rounded native label is not an exact order quantity. */
final class OwnOrderQuantity {
    private static final Pattern NUMBER = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)([kmb]?)");
    private OwnOrderQuantity() {}

    static OptionalInt exact(String display) {
        if (display == null || display.length() > 64) return OptionalInt.empty();
        String clean = display.strip().toLowerCase(Locale.ROOT);
        // Accept grouping only in its whole-number form, never reinterpret a decimal comma.
        if (clean.contains(",")) {
            if (!clean.matches("[0-9]{1,3}(?:,[0-9]{3})+")) return OptionalInt.empty();
            clean = clean.replace(",", "");
        }
        var match = NUMBER.matcher(clean);
        if (!match.matches()) return OptionalInt.empty();
        int power = switch (match.group(2)) { case "k" -> 3; case "m" -> 6; case "b" -> 9; default -> 0; };
        String value = match.group(1);
        int decimals = value.contains(".") ? value.length() - value.indexOf('.') - 1 : 0;
        if (power > decimals) return OptionalInt.empty();
        try {
            int quantity = new BigDecimal(value).movePointRight(power).intValueExact();
            return quantity >= 0 ? OptionalInt.of(quantity) : OptionalInt.empty();
        } catch (ArithmeticException | NumberFormatException invalid) {
            return OptionalInt.empty();
        }
    }
}

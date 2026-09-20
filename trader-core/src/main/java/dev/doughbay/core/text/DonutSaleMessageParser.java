package dev.doughbay.core.text;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the server-authored notification emitted when another player buys one
 * of the local player's auction listings.
 *
 * <p>This parser is deliberately narrow. It accepts only a complete message in
 * the form {@code BuyerName bought your Item Name [xN] for $price}. A caller
 * should still feed it only server/game messages, never ordinary player chat,
 * because another player can type text that resembles a sale notification.
 */
public final class DonutSaleMessageParser {

    private static final int MAX_MESSAGE_LENGTH = 256;
    private static final int MAX_ITEM_TEXT_LENGTH = 128;
    private static final int MAX_STACK_COUNT = 64;

    private static final String NUMBER =
            "(?:[1-9]\\d{0,2}(?:,\\d{3})+|0|[1-9]\\d*)(?:\\.\\d+)?";

    private static final Pattern SALE_MESSAGE = Pattern.compile(
            "^(?<buyer>\\.?[A-Za-z0-9_]{3,16}) bought your "
                    + "(?<item>\\S(?:.*\\S)?) for \\$(?<amount>"
                    + NUMBER
                    + ")(?<suffix>[kKmMbB]?)$",
            Pattern.DOTALL);

    private static final Pattern TRAILING_COUNT = Pattern.compile(
            "^(?<item>.+?)\\s+(?:x|\\u00d7)\\s*(?<count>\\d+)$",
            Pattern.CASE_INSENSITIVE);

    private DonutSaleMessageParser() {
    }

    /**
     * Attempts to parse one complete, visible game-message string.
     *
     * @param rawText text obtained from the received Minecraft component
     * @return a normalized sale notice, or empty when the message is not an
     *         unambiguous Donut sale notification
     */
    public static Optional<SaleNotice> parse(CharSequence rawText) {
        if (rawText == null || rawText.length() == 0 || rawText.length() > MAX_MESSAGE_LENGTH) {
            return Optional.empty();
        }

        String text = normalize(rawText);
        if (text == null || text.isEmpty() || text.length() > MAX_MESSAGE_LENGTH) {
            return Optional.empty();
        }

        Matcher matcher = SALE_MESSAGE.matcher(text);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        String buyer = matcher.group("buyer");
        String itemWithCount = matcher.group("item");
        if (itemWithCount.length() > MAX_ITEM_TEXT_LENGTH
                || containsIgnoreCase(itemWithCount, " for $")) {
            return Optional.empty();
        }

        long price;
        long priceUpperBound;
        try {
            price = parsePrice(matcher.group("amount"), matcher.group("suffix"));
            priceUpperBound = Math.addExact(price,
                    priceStep(matcher.group("amount"), matcher.group("suffix")) - 1);
        } catch (ArithmeticException | NumberFormatException ex) {
            return Optional.empty();
        }
        if (price <= 0) {
            return Optional.empty();
        }

        String itemText = itemWithCount;
        OptionalInt count = OptionalInt.empty();
        Matcher countMatcher = TRAILING_COUNT.matcher(itemWithCount);
        if (countMatcher.matches()) {
            int parsedCount;
            try {
                parsedCount = Integer.parseInt(countMatcher.group("count"));
            } catch (NumberFormatException ex) {
                return Optional.empty();
            }
            if (parsedCount < 1 || parsedCount > MAX_STACK_COUNT) {
                return Optional.empty();
            }
            itemText = countMatcher.group("item").strip();
            count = OptionalInt.of(parsedCount);
        }

        if (itemText.isEmpty() || itemText.length() > MAX_ITEM_TEXT_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(new SaleNotice(buyer, itemText, count, price, priceUpperBound));
    }

    /**
     * The smallest amount the printed price can distinguish: 1 when it is
     * exact, and 10^(suffix digits - decimals) when abbreviated. The server
     * truncates rather than rounds, so "$12.2K" means 12,200 to 12,299.
     */
    private static long priceStep(String amountText, String suffixText) {
        if (suffixText.isEmpty()) return 1L;
        int decimals = amountText.contains(".")
                ? amountText.length() - amountText.indexOf('.') - 1 : 0;
        long step = switch (suffixText.toLowerCase(Locale.ROOT)) {
            case "k" -> 1_000L;
            case "m" -> 1_000_000L;
            case "b" -> 1_000_000_000L;
            default -> throw new NumberFormatException("Unsupported currency suffix");
        };
        for (int i = 0; i < decimals && step > 1; i++) step /= 10;
        return step;
    }

    private static long parsePrice(String amountText, String suffixText) {
        BigDecimal amount = new BigDecimal(amountText.replace(",", ""));
        long multiplier = switch (suffixText.toLowerCase(Locale.ROOT)) {
            case "" -> 1L;
            case "k" -> 1_000L;
            case "m" -> 1_000_000L;
            case "b" -> 1_000_000_000L;
            default -> throw new NumberFormatException("Unsupported currency suffix");
        };
        return amount.multiply(BigDecimal.valueOf(multiplier)).longValueExact();
    }

    /**
     * Removes legacy color codes and collapses horizontal spacing. Newlines and
     * other control characters are rejected instead of being silently joined.
     */
    private static String normalize(CharSequence rawText) {
        StringBuilder normalized = new StringBuilder(rawText.length());
        boolean pendingSpace = false;

        for (int i = 0; i < rawText.length(); i++) {
            char character = rawText.charAt(i);
            if (character == '\u00a7') {
                if (i + 1 >= rawText.length() || !isLegacyFormattingCode(rawText.charAt(i + 1))) {
                    return null;
                }
                i++;
                continue;
            }
            if (Character.isISOControl(character)) {
                return null;
            }
            if (Character.isWhitespace(character) || Character.isSpaceChar(character)) {
                pendingSpace = normalized.length() > 0;
                continue;
            }
            if (pendingSpace) {
                normalized.append(' ');
                pendingSpace = false;
            }
            normalized.append(character);
        }

        return normalized.toString().strip();
    }

    private static boolean isLegacyFormattingCode(char character) {
        char lower = Character.toLowerCase(character);
        return (lower >= '0' && lower <= '9')
                || (lower >= 'a' && lower <= 'f')
                || (lower >= 'k' && lower <= 'o')
                || lower == 'r';
    }

    private static boolean containsIgnoreCase(String text, String fragment) {
        return text.toLowerCase(Locale.ROOT).contains(fragment.toLowerCase(Locale.ROOT));
    }

    /**
     * A normalized completed-sale notification. The server abbreviates the
     * price, so {@code price} is the lowest amount the text can mean and
     * {@code priceUpperBound} the highest; they are equal when it was exact.
     */
    public record SaleNotice(String buyer, String itemText, OptionalInt count, long price,
                             long priceUpperBound) {
        public SaleNotice {
            buyer = Objects.requireNonNull(buyer, "buyer");
            itemText = Objects.requireNonNull(itemText, "itemText");
            count = Objects.requireNonNull(count, "count");
            if (buyer.isBlank() || itemText.isBlank()) {
                throw new IllegalArgumentException("Buyer and item text must not be blank");
            }
            if (price <= 0) {
                throw new IllegalArgumentException("Price must be positive");
            }
            if (priceUpperBound < price) {
                throw new IllegalArgumentException("Price band must not be below the price");
            }
        }

        /** An exact-price notice. */
        public SaleNotice(String buyer, String itemText, OptionalInt count, long price) {
            this(buyer, itemText, count, price, price);
        }

        /** Whether the printed price could have meant {@code exact}. */
        public boolean couldBe(long exact) {
            return exact >= price && exact <= priceUpperBound;
        }
    }
}

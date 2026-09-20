package dev.doughbay.core.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Small, dependency-free JSON canonicalizer for identity-bearing item metadata.
 * Object keys are sorted, numbers are normalized, and insignificant whitespace
 * is removed. Duplicate keys are rejected because their interpretation differs
 * between JSON libraries and therefore cannot safely identify an item.
 */
public final class CanonicalJson {

    public static final Limits DEFAULT_LIMITS = new Limits(32, 4_096, 1_048_576);

    private CanonicalJson() {
    }

    public static String canonicalize(String json) {
        return canonicalize(json, DEFAULT_LIMITS);
    }

    public static String canonicalize(String json, Limits limits) {
        Objects.requireNonNull(limits, "limits");
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON is required");
        }
        Parser parser = new Parser(json, limits);
        JsonValue value = parser.parse();
        StringBuilder result = new StringBuilder(json.length());
        value.appendTo(result);
        return result.toString();
    }

    public record Limits(int maxDepth, int maxEntries, int maxStringLength) {
        public Limits {
            if (maxDepth < 1) throw new IllegalArgumentException("maxDepth must be positive");
            if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
            if (maxStringLength < 1) {
                throw new IllegalArgumentException("maxStringLength must be positive");
            }
        }
    }

    private interface JsonValue {
        void appendTo(StringBuilder target);
    }

    private record JsonObject(SortedMap<String, JsonValue> members) implements JsonValue {
        @Override
        public void appendTo(StringBuilder target) {
            target.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonValue> member : members.entrySet()) {
                if (!first) target.append(',');
                appendString(target, member.getKey());
                target.append(':');
                member.getValue().appendTo(target);
                first = false;
            }
            target.append('}');
        }
    }

    private record JsonArray(List<JsonValue> values) implements JsonValue {
        @Override
        public void appendTo(StringBuilder target) {
            target.append('[');
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) target.append(',');
                values.get(i).appendTo(target);
            }
            target.append(']');
        }
    }

    private record JsonString(String value) implements JsonValue {
        @Override
        public void appendTo(StringBuilder target) {
            appendString(target, value);
        }
    }

    private record JsonNumber(BigDecimal value) implements JsonValue {
        @Override
        public void appendTo(StringBuilder target) {
            BigDecimal normalized = value.signum() == 0
                    ? BigDecimal.ZERO
                    : value.stripTrailingZeros();
            target.append(normalized.toPlainString());
        }
    }

    private enum JsonLiteral implements JsonValue {
        TRUE("true"), FALSE("false"), NULL("null");

        private final String text;

        JsonLiteral(String text) {
            this.text = text;
        }

        @Override
        public void appendTo(StringBuilder target) {
            target.append(text);
        }
    }

    private static void appendString(StringBuilder target, String value) {
        target.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> target.append("\\\"");
                case '\\' -> target.append("\\\\");
                case '\b' -> target.append("\\b");
                case '\f' -> target.append("\\f");
                case '\n' -> target.append("\\n");
                case '\r' -> target.append("\\r");
                case '\t' -> target.append("\\t");
                default -> {
                    if (c < 0x20) {
                        target.append(String.format("\\u%04x", (int) c));
                    } else {
                        target.append(c);
                    }
                }
            }
        }
        target.append('"');
    }

    private static final class Parser {
        private final String source;
        private final Limits limits;
        private int index;
        private int entries;

        private Parser(String source, Limits limits) {
            this.source = source;
            this.limits = limits;
        }

        private JsonValue parse() {
            skipWhitespace();
            JsonValue value = parseValue(1);
            skipWhitespace();
            if (index != source.length()) throw error("unexpected trailing content");
            return value;
        }

        private JsonValue parseValue(int depth) {
            if (depth > limits.maxDepth()) throw error("JSON depth limit exceeded");
            skipWhitespace();
            if (index >= source.length()) throw error("expected a JSON value");
            return switch (source.charAt(index)) {
                case '{' -> parseObject(depth);
                case '[' -> parseArray(depth);
                case '"' -> new JsonString(parseString());
                case 't' -> parseLiteral("true", JsonLiteral.TRUE);
                case 'f' -> parseLiteral("false", JsonLiteral.FALSE);
                case 'n' -> parseLiteral("null", JsonLiteral.NULL);
                default -> parseNumber();
            };
        }

        private JsonObject parseObject(int depth) {
            expect('{');
            SortedMap<String, JsonValue> members = new TreeMap<>();
            skipWhitespace();
            if (consume('}')) return new JsonObject(members);
            while (true) {
                skipWhitespace();
                if (index >= source.length() || source.charAt(index) != '"') {
                    throw error("object key must be a string");
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                countEntry();
                JsonValue previous = members.putIfAbsent(key, parseValue(depth + 1));
                if (previous != null) throw error("duplicate object key: " + key);
                skipWhitespace();
                if (consume('}')) break;
                expect(',');
            }
            return new JsonObject(members);
        }

        private JsonArray parseArray(int depth) {
            expect('[');
            List<JsonValue> values = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) return new JsonArray(values);
            while (true) {
                countEntry();
                values.add(parseValue(depth + 1));
                skipWhitespace();
                if (consume(']')) break;
                expect(',');
            }
            return new JsonArray(List.copyOf(values));
        }

        private JsonValue parseLiteral(String expected, JsonLiteral result) {
            if (!source.startsWith(expected, index)) throw error("invalid JSON value");
            index += expected.length();
            return result;
        }

        private JsonNumber parseNumber() {
            int start = index;
            consume('-');
            if (consume('0')) {
                if (index < source.length() && Character.isDigit(source.charAt(index))) {
                    throw error("leading zero in number");
                }
            } else {
                requireDigits();
            }
            if (consume('.')) requireDigits();
            if (index < source.length()
                    && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
                index++;
                if (!consume('+')) consume('-');
                requireDigits();
            }
            if (start == index) throw error("invalid JSON value");
            try {
                return new JsonNumber(new BigDecimal(source.substring(start, index)));
            } catch (NumberFormatException ex) {
                throw error("invalid number");
            }
        }

        private void requireDigits() {
            int start = index;
            while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
            if (start == index) throw error("expected digit");
        }

        private String parseString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (index < source.length()) {
                char c = source.charAt(index++);
                if (c == '"') {
                    if (value.length() > limits.maxStringLength()) {
                        throw error("JSON string length limit exceeded");
                    }
                    return value.toString();
                }
                if (c < 0x20) throw error("unescaped control character in string");
                if (c != '\\') {
                    value.append(c);
                    continue;
                }
                if (index >= source.length()) throw error("unterminated escape sequence");
                char escaped = source.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> value.append(parseUnicodeEscape());
                    default -> throw error("invalid escape sequence");
                }
                if (value.length() > limits.maxStringLength()) {
                    throw error("JSON string length limit exceeded");
                }
            }
            throw error("unterminated string");
        }

        private char parseUnicodeEscape() {
            if (index + 4 > source.length()) throw error("incomplete unicode escape");
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(source.charAt(index++), 16);
                if (digit < 0) throw error("invalid unicode escape");
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private void countEntry() {
            if (++entries > limits.maxEntries()) throw error("JSON entry limit exceeded");
        }

        private void skipWhitespace() {
            while (index < source.length()) {
                char c = source.charAt(index);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') return;
                index++;
            }
        }

        private boolean consume(char expected) {
            if (index < source.length() && source.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) throw error("expected '" + expected + "'");
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at character " + index);
        }
    }
}

package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A whole tuning setup packed into one shareable code, and back again.
 *
 * <p>The values are written to compact JSON, gzipped, and spelled in Base32 -
 * the digits and capitals a person can read aloud or type without ambiguity,
 * grouped in fives behind a {@code DB1-} tag so a pasted code is recognisable
 * and a truncated one is caught. It carries only tuning: never the API key
 * (which lives in a separate file the code never reads) and never the webhook
 * URL, which is a secret the sender would not mean to hand over.
 *
 * <p>Importing applies every value the code carries and ignores any key this
 * build does not know, so a code from a newer or older version still loads what
 * it can rather than failing whole.
 */
public final class SettingsCode {
    private static final int VERSION = 1;
    private static final String PREFIX = "DB1";
    /** Never leaves this machine in a shared code. */
    private static final Set<String> NEVER_EXPORT = Set.of("webhook.url");
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private SettingsCode() {
    }

    /** The result of importing a code: how many values applied, or why it failed. */
    public record Result(boolean ok, int applied, int skipped, String error) {
        public static Result failed(String why) {
            return new Result(false, 0, 0, why);
        }
    }

    /** Packs the current tuning into a shareable code. */
    public static String export() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("v", VERSION);
        ObjectNode numbers = root.putObject("n");
        for (Tuning.Setting s : Tuning.SETTINGS) {
            numbers.put(s.key(), Tuning.get(s.key()));
        }
        ObjectNode texts = root.putObject("t");
        for (Tuning.ListSetting l : Tuning.LISTS) {
            if (NEVER_EXPORT.contains(l.key())) continue;
            String value = Tuning.text(l.key());
            if (!value.isBlank()) texts.put(l.key(), value);
        }
        try {
            byte[] json = mapper.writeValueAsBytes(root);
            String body = base32Encode(gzip(json));
            return PREFIX + "-" + group(body);
        } catch (Exception e) {
            return "";
        }
    }

    /** Applies a code to the live tuning; returns what it did or why it could not. */
    public static Result apply(String code) {
        if (code == null || code.isBlank()) return Result.failed("empty code");
        byte[] gz;
        try {
            gz = base32Decode(code);
        } catch (RuntimeException e) {
            return Result.failed("not a valid code");
        }
        byte[] json;
        try {
            json = gunzip(gz);
        } catch (Exception e) {
            return Result.failed("code is corrupt or truncated");
        }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(json);
        } catch (Exception e) {
            return Result.failed("code is corrupt");
        }
        if (root == null || !root.hasNonNull("v")) return Result.failed("not a settings code");

        int applied = 0;
        int skipped = 0;
        JsonNode numbers = root.get("n");
        if (numbers != null && numbers.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = numbers.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (Tuning.setting(e.getKey()) == null) {
                    skipped++;
                    continue;
                }
                Tuning.set(e.getKey(), e.getValue().asDouble());
                applied++;
            }
        }
        JsonNode texts = root.get("t");
        if (texts != null && texts.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = texts.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey();
                if (NEVER_EXPORT.contains(key) || !isKnownList(key)) {
                    skipped++;
                    continue;
                }
                Tuning.setText(key, e.getValue().asText(""));
                applied++;
            }
        }
        if (applied == 0) return Result.failed("code carried nothing this version understands");
        return new Result(true, applied, skipped, "");
    }

    private static boolean isKnownList(String key) {
        for (Tuning.ListSetting l : Tuning.LISTS) if (l.key().equals(key)) return true;
        return false;
    }

    // ---- transport ----------------------------------------------------------

    private static byte[] gzip(byte[] data) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] data) throws java.io.IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return gz.readAllBytes();
        }
    }

    /** Groups the code in fives with dashes, so it reads like a key rather than a wall. */
    private static String group(String body) {
        StringBuilder out = new StringBuilder(body.length() + body.length() / 5);
        for (int i = 0; i < body.length(); i++) {
            if (i > 0 && i % 5 == 0) out.append('-');
            out.append(body.charAt(i));
        }
        return out.toString();
    }

    private static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(ALPHABET.charAt((buffer >> bits) & 0x1F));
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return out.toString();
    }

    private static byte[] base32Decode(String code) {
        String clean = code.trim().toUpperCase(java.util.Locale.ROOT);
        if (clean.startsWith(PREFIX)) clean = clean.substring(PREFIX.length());
        StringBuilder symbols = new StringBuilder();
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (ALPHABET.indexOf(c) >= 0) symbols.append(c);
        }
        if (symbols.length() == 0) throw new IllegalArgumentException("no code");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < symbols.length(); i++) {
            buffer = (buffer << 5) | ALPHABET.indexOf(symbols.charAt(i));
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}

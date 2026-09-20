package dev.doughbay.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Scrambles the player's own name where this client draws it, for
 * screenshots and video: the name tag over the head while the camera orbits,
 * and the sidebar scoreboard.
 *
 * <p>Display only. Nothing is sent to the server differently and nothing the
 * bot reads is changed: the scoreboard's own data is untouched, so the balance
 * read from the sidebar carries on as before. The letters are drawn with the
 * game's obfuscated style, which keeps the line the same width.
 */
public final class NameMask {
    private NameMask() {
    }

    public static boolean on() {
        return Tuning.get("privacy.hide_name") >= 0.5;
    }

    /** {@code text} with the local player's name scrambled, or {@code text} itself when there is nothing to hide. */
    public static Component mask(Component text) {
        if (text == null || !on()) return text;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getUser() == null) return text;
        String name = client.getUser().getName();
        List<Style> styles = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        text.visit((style, part) -> {
            styles.add(style);
            parts.add(part);
            return Optional.empty();
        }, Style.EMPTY);
        boolean[] hidden = hiddenChars(String.join("", parts), name);
        if (hidden == null) return text;
        MutableComponent out = Component.empty();
        int at = 0;
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            int from = 0;
            while (from < part.length()) {
                boolean hide = hidden[at + from];
                int to = from;
                while (to < part.length() && hidden[at + to] == hide) to++;
                Style style = hide ? styles.get(i).withObfuscated(true) : styles.get(i);
                out.append(Component.literal(part.substring(from, to)).setStyle(style));
                from = to;
            }
            at += part.length();
        }
        return out;
    }

    /**
     * Which characters of {@code line} belong to an occurrence of {@code name},
     * ignoring case; null when it does not occur. The line is taken whole, so a
     * name the server colours letter by letter is still found. Package-visible
     * for tests.
     */
    static boolean[] hiddenChars(String line, String name) {
        if (line == null || name == null || name.isBlank()) return null;
        String haystack = line.toLowerCase(Locale.ROOT);
        String needle = name.toLowerCase(Locale.ROOT);
        if (haystack.length() != line.length()) return null;
        boolean[] hidden = null;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            if (hidden == null) hidden = new boolean[line.length()];
            Arrays.fill(hidden, i, i + needle.length(), true);
        }
        return hidden;
    }
}

package dev.doughbay.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The balance the server's own sidebar shows ("$ 789M"), read every couple of
 * seconds.
 *
 * <p>The API balance is exact to the coin but arrives with the feed sweep, a
 * minute or more behind; the sidebar moves the instant money does but only to
 * the displayed resolution. {@link #best} keeps the API figure while it still
 * agrees with the sidebar and takes the sidebar's when it no longer does, so
 * what Discord shows is both current and, when it can be, exact.
 */
public final class SidebarBalance {
    private static final Pattern MONEY = Pattern.compile("\\$\\s*([0-9][0-9.,]*)\\s*([KkMmBbTt])?(?![A-Za-z])");
    /** Older than this and the sidebar reading is not trusted over the API. */
    private static final long FRESH_MILLIS = 15_000L;

    private static volatile long shown = -1;
    private static volatile long band = 1;
    private static volatile long readAt;
    private static int ticks;

    private SidebarBalance() { }

    /** From the client tick; reads the sidebar every forty ticks. */
    public static void tick(Minecraft client) {
        if (++ticks % 40 != 0) return;
        try {
            if (client == null || client.level == null) return;
            Scoreboard board = client.level.getScoreboard();
            Objective sidebar = board.getDisplayObjective(DisplaySlot.SIDEBAR);
            if (sidebar == null) return;
            long[] found = parse(sidebar.getDisplayName().getString());
            for (PlayerScoreEntry entry : board.listPlayerScores(sidebar)) {
                if (found != null) break;
                if (entry.isHidden()) continue;
                PlayerTeam team = board.getPlayersTeam(entry.owner());
                String line = PlayerTeam.formatNameForTeam(team, entry.ownerName()).getString();
                if (entry.display() != null) line += " " + entry.display().getString();
                found = parse(line);
            }
            if (found == null) return;
            shown = found[0];
            band = found[1];
            readAt = System.currentTimeMillis();
        } catch (RuntimeException ignored) {
            // A sidebar laid out some other way is simply not read.
        }
    }

    /**
     * The balance to show: the exact API figure while it falls inside what the
     * sidebar displays, else the sidebar's (the bottom of its range, so it is
     * never overstated). Either alone when the other is missing.
     */
    public static long best(long apiBalance) {
        long s = shown;
        if (s < 0 || System.currentTimeMillis() - readAt > FRESH_MILLIS) return apiBalance;
        if (apiBalance >= s && apiBalance < s + band) return apiBalance;
        return s;
    }

    /** The sidebar's balance (the bottom of its range) while fresh, else -1. The balance with no API. */
    public static long current() {
        long s = shown;
        return s >= 0 && System.currentTimeMillis() - readAt <= FRESH_MILLIS ? s : -1;
    }

    /** {shown, band} for the first "$ amount" in a line, or null. Package-visible for tests. */
    static long[] parse(String text) {
        if (text == null) return null;
        Matcher m = MONEY.matcher(text);
        if (!m.find()) return null;
        String digits = m.group(1).replace(",", "");
        String suffix = m.group(2);
        if (digits.isEmpty() || digits.endsWith(".")) return null;
        long unit = switch (suffix == null ? ' ' : Character.toLowerCase(suffix.charAt(0))) {
            case 'k' -> 1_000L;
            case 'm' -> 1_000_000L;
            case 'b' -> 1_000_000_000L;
            case 't' -> 1_000_000_000_000L;
            default -> 1L;
        };
        try {
            long value = new java.math.BigDecimal(digits).multiply(java.math.BigDecimal.valueOf(unit)).longValueExact();
            return new long[] {value, AutomatedExecutionDriver.displayedBandFor(digits,
                    suffix == null ? null : suffix.toUpperCase(Locale.ROOT))};
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }
}

package dev.doughbay.fabric.discord;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Draws one page of the Discord embed as a PNG in the HUD's colours: a
 * title line, a few headline numbers, an optional slot grid, and a table.
 * Pure Java2D, so it works headless inside the game process.
 */
public final class StatsImage {
    static final int WIDTH = 960;
    /**
     * Pixels drawn per unit of layout. The page is composed at 960 wide and
     * rendered at twice that, so the text is sharp on a high-resolution screen
     * without every coordinate in here having to change.
     */
    private static final double SCALE = 2.0;
    private static final Color BG = new Color(16, 17, 22);
    private static final Color PANEL = new Color(26, 28, 36);
    private static final Color LINE = new Color(48, 52, 66);
    private static final Color TEXT = new Color(226, 228, 235);
    private static final Color DIM = new Color(140, 146, 165);
    private static final Color GOLD = new Color(245, 197, 66);
    private static final Color GREEN = new Color(92, 214, 132);
    private static final Color RED = new Color(235, 96, 96);
    private static final Color BLUE = new Color(96, 156, 245);
    private static final Color SLOT_ON = new Color(92, 214, 132);
    private static final Color SLOT_OFF = new Color(40, 44, 56);

    /**
     * One page: headline pairs, optional grid, table with per-column
     * alignment, and one item id per row whose picture is drawn beside it.
     */
    public record Page(String title, String subtitle, String stateColor, List<String[]> headline,
                int gridUsed, int gridTotal, String[] columns, boolean[] rightAlign, List<String[]> rows,
                List<String> rowIcons, int iconColumn, String footer, List<String> faces,
                List<String> rowFaces, String mascotFace) {

        public Page(String title, String subtitle, String stateColor, List<String[]> headline,
             int gridUsed, int gridTotal, String[] columns, boolean[] rightAlign, List<String[]> rows,
             List<String> rowIcons, int iconColumn, String footer) {
            this(title, subtitle, stateColor, headline, gridUsed, gridTotal, columns, rightAlign, rows,
                    rowIcons, iconColumn, footer, List.of(), List.of(), "");
        }

        /**
         * The accounts whose heads belong in the header. Kept off every
         * constructor because whose page it is is decided when it is published,
         * not when it is built.
         */
        public Page withFaces(List<String> accounts) {
            return new Page(title, subtitle, stateColor, headline, gridUsed, gridTotal, columns,
                    rightAlign, rows, rowIcons, iconColumn, footer, accounts, rowFaces, mascotFace);
        }

        /**
         * One account per row, drawn as a head at the far left. Empty leaves
         * the table exactly as it was, which is what a page about a single
         * account wants: every row is the same account, and a column of the
         * same face is a column of noise.
         */
        public Page withRowFaces(List<String> accounts) {
            return new Page(title, subtitle, stateColor, headline, gridUsed, gridTotal, columns,
                    rightAlign, rows, rowIcons, iconColumn, footer, faces, accounts, mascotFace);
        }

        /** Which face the robot in the header wears; empty draws no robot. */
        public Page withMascot(String face) {
            return new Page(title, subtitle, stateColor, headline, gridUsed, gridTotal, columns,
                    rightAlign, rows, rowIcons, iconColumn, footer, faces, rowFaces, face);
        }

        public Page(String title, String subtitle, String stateColor, List<String[]> headline,
             int gridUsed, int gridTotal, String[] columns, boolean[] rightAlign, List<String[]> rows, String footer) {
            this(title, subtitle, stateColor, headline, gridUsed, gridTotal, columns, rightAlign, rows, List.of(), 0, footer);
        }

        public Page(String title, String subtitle, String stateColor, List<String[]> headline,
             int gridUsed, int gridTotal, String[] columns, boolean[] rightAlign, List<String[]> rows,
             List<String> rowIcons, String footer) {
            this(title, subtitle, stateColor, headline, gridUsed, gridTotal, columns, rightAlign, rows, rowIcons, 0, footer);
        }
    }

    /** The header's column unit: a third of the usable width. */
    private static final int HEAD_COLUMNS = 3;

    /**
     * How many columns each headline pair needs.
     *
     * <p>The header used to drop every pair into a fixed third whatever it
     * measured, so "Revenue 24h $92.30M - margin 16.6%" was drawn straight
     * through the pair beside it. A pair now claims as many columns as its
     * text actually needs and the line wraps when they run out.
     */
    /**
     * A row of bars for a series of hourly figures, newest at the right.
     *
     * <p>The value is "label|v1,v2,...,vN". Bars scale to the largest
     * magnitude in the series so the shape reads at a glance: where this
     * hour sits against the ones before it, which a single number cannot
     * say. Losses draw red, the newest hour gold, the rest green; an hour
     * with nothing in it is a baseline tick rather than a gap, so the row
     * keeps its rhythm. The newest value and the peak are printed at the
     * right so the picture also carries the figures.
     */
    private static void drawSparkline(Graphics2D g, String value, int x, int y, int width,
                                      Font labelFont, Font small) {
        int bar = value.indexOf('|');
        String label = bar > 0 ? value.substring(0, bar) : "Profit/h";
        String[] nums = (bar > 0 ? value.substring(bar + 1) : value).split(",");
        long[] v = new long[nums.length];
        long max = 1;
        for (int i = 0; i < nums.length; i++) {
            try {
                v[i] = Long.parseLong(nums[i].trim());
            } catch (NumberFormatException e) {
                v[i] = 0;
            }
            max = Math.max(max, Math.abs(v[i]));
        }
        g.setFont(labelFont);
        g.setColor(DIM);
        g.drawString(label, x, y + 23);
        int lx = x + g.getFontMetrics().stringWidth(label + "  ");
        int barW = 14;
        int gap = 5;
        int h = 22;
        int base = y + 26;
        for (int i = 0; i < v.length; i++) {
            int bh = (int) Math.round(h * Math.abs(v[i]) / (double) max);
            if (bh < 2 && v[i] != 0) bh = 2;
            int bx = lx + i * (barW + gap);
            boolean newest = i == v.length - 1;
            if (bh > 0) {
                g.setColor(v[i] < 0 ? RED : (newest ? GOLD : GREEN));
                g.fillRoundRect(bx, base - bh, barW, bh, 3, 3);
            } else {
                g.setColor(SLOT_OFF);
                g.fillRect(bx, base - 1, barW, 1);
            }
        }
        g.setFont(small);
        g.setColor(TEXT);
        String tail = "now " + compact(v[v.length - 1]) + "  ·  peak " + compact(max);
        g.drawString(tail, x + width - g.getFontMetrics().stringWidth(tail), y + 22);
    }

    /** 7.3M, 612K, 950 - the way the figures read on the panel. */
    private static String compact(long v) {
        long a = Math.abs(v);
        String s = a >= 1_000_000 ? String.format(java.util.Locale.ROOT, "%.1fM", a / 1_000_000.0)
                : a >= 1_000 ? String.format(java.util.Locale.ROOT, "%.0fK", a / 1_000.0)
                : Long.toString(a);
        return (v < 0 ? "-$" : "$") + s;
    }

    private static int[] headlineSpans(FontMetrics fm, List<String[]> headline, int unit) {
        int[] spans = new int[headline.size()];
        for (int i = 0; i < headline.size(); i++) {
            String[] kv = headline.get(i);
            // A sparkline takes a whole line of its own, whatever it measures.
            if (kv[0].startsWith("~spark")) {
                spans[i] = HEAD_COLUMNS;
                continue;
            }
            int width = fm.stringWidth(kv[0] + "  ") + fm.stringWidth(kv[1]) + 18;
            spans[i] = Math.max(1, Math.min(HEAD_COLUMNS, (width + unit - 1) / unit));
        }
        return spans;
    }

    /** Lines those spans take, which is what the image has to be tall enough for. */
    private static int headlineLines(int[] spans) {
        if (spans.length == 0) return 0;
        int lines = 1;
        int used = 0;
        for (int span : spans) {
            if (used + span > HEAD_COLUMNS) {
                lines++;
                used = 0;
            }
            used += span;
        }
        return lines;
    }

    public static byte[] render(Page page) throws IOException {
        Font headFont = new Font(Font.SANS_SERIF, Font.PLAIN, 24);
        int headUnit = (WIDTH - 56) / HEAD_COLUMNS;
        // Measured before the image exists, because the measurement decides
        // how tall the image has to be.
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D probeG = probe.createGraphics();
        probeG.setFont(headFont);
        int[] headSpans = headlineSpans(probeG.getFontMetrics(), page.headline(), headUnit);
        probeG.dispose();

        int rowsHeight = 33 * Math.max(1, page.rows().size()) + 42;
        int gridHeight = page.gridTotal() > 0 ? 20 + 14 * ((page.gridTotal() + 29) / 30) + 8 : 0;
        int headlineHeight = page.headline().isEmpty() ? 0 : 31 * headlineLines(headSpans) + 14;
        int height = 86 + headlineHeight + gridHeight + rowsHeight + 46;
        BufferedImage img = new BufferedImage(
                (int) Math.round(WIDTH * SCALE), (int) Math.round(height * SCALE), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.scale(SCALE, SCALE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Item pictures are 16 pixels square: keep them crisp, not blurred.
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setColor(BG);
        g.fillRect(0, 0, WIDTH, height);
        g.setColor(PANEL);
        g.fillRoundRect(10, 10, WIDTH - 20, height - 20, 16, 16);
        g.setColor(LINE);
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(10, 10, WIDTH - 20, height - 20, 16, 16);

        Font title = new Font(Font.SANS_SERIF, Font.BOLD, 34);
        Font body = headFont;
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 22);
        Font small = new Font(Font.SANS_SERIF, Font.PLAIN, 18);

        int y = 54;
        // The wordmark and the robot from the mod's own assets, so the panel
        // and the screen in the game look like the same thing. Either can be
        // missing - a page still has to draw - and then it falls back to type.
        int brand = 28;
        java.awt.image.BufferedImage wordmark = Branding.logo();
        if (wordmark != null) {
            int logoHeight = 32;
            int logoWidth = wordmark.getWidth() * logoHeight / wordmark.getHeight();
            g.drawImage(wordmark, brand, y - logoHeight + 6, logoWidth, logoHeight, null);
            brand += logoWidth + 10;
        } else {
            g.setFont(title);
            g.setColor(GOLD);
            g.drawString("GoNuts", brand, y);
            brand += g.getFontMetrics().stringWidth("GoNuts ");
        }
        java.awt.image.BufferedImage robot = page.mascotFace().isBlank() ? null
                : Branding.mascot(page.mascotFace());
        if (robot != null) {
            int robotHeight = 34;
            int robotWidth = robot.getWidth() * robotHeight / robot.getHeight();
            g.drawImage(robot, brand, y - robotHeight + 7, robotWidth, robotHeight, null);
            brand += robotWidth + 12;
        }
        g.setFont(title);
        g.setColor(colorOf(page.stateColor()));
        g.drawString(page.title(), brand, y);
        // Whose page this is, at the right of the title row: the face first,
        // because that is what gets read, then the name, then the figures that
        // were already there in small grey text.
        g.setFont(small);
        FontMetrics sm = g.getFontMetrics();
        int right = WIDTH - 28;
        Font nameFont = new Font(Font.SANS_SERIF, Font.BOLD, 22);
        String who = page.faces().size() == 1 ? page.faces().getFirst() : "";
        java.util.List<java.awt.image.BufferedImage> heads = new java.util.ArrayList<>();
        for (String account : page.faces()) {
            java.awt.image.BufferedImage head = Avatars.head(account);
            if (head != null) heads.add(head);
        }
        int nameWidth = 0;
        if (!who.isBlank()) {
            g.setFont(nameFont);
            nameWidth = g.getFontMetrics().stringWidth(who);
        }
        int faceBlock = heads.isEmpty() ? 0 : heads.size() * 30 + (heads.size() - 1) * 4 + 10;
        int subWidth = sm.stringWidth(page.subtitle());
        int blockLeft = right - subWidth - (nameWidth > 0 ? nameWidth + 12 : 0) - faceBlock;
        int whoX = blockLeft;
        for (java.awt.image.BufferedImage head : heads) {
            g.drawImage(head, whoX, y - 24, 30, 30, null);
            whoX += 34;
        }
        if (!heads.isEmpty()) whoX += 6;
        if (!who.isBlank()) {
            g.setFont(nameFont);
            g.setColor(TEXT);
            g.drawString(who, whoX, y - 2);
        }
        g.setFont(small);
        g.setColor(DIM);
        g.drawString(page.subtitle(), right - subWidth, y);
        y += 18;
        g.setColor(LINE);
        g.drawLine(28, y, WIDTH - 28, y);
        y += 10;

        if (!page.headline().isEmpty()) {
            g.setFont(body);
            int used = 0;
            for (int i = 0; i < page.headline().size(); i++) {
                if (used + headSpans[i] > HEAD_COLUMNS) {
                    used = 0;
                    y += 31;
                }
                String[] kv = page.headline().get(i);
                int x = 28 + used * headUnit;
                if (kv[0].startsWith("~spark")) {
                    drawSparkline(g, kv[1], x, y, WIDTH - 56, body, small);
                    used += headSpans[i];
                    continue;
                }
                g.setColor(DIM);
                g.drawString(kv[0], x, y + 23);
                int lw = g.getFontMetrics().stringWidth(kv[0] + "  ");
                g.setColor(kv.length > 2 ? colorOf(kv[2]) : TEXT);
                g.drawString(kv[1], x + lw, y + 23);
                used += headSpans[i];
            }
            y += 31;
            y += 14;
        }

        if (page.gridTotal() > 0) {
            g.setFont(small);
            g.setColor(DIM);
            g.drawString("Auction slots " + page.gridUsed() + " / " + page.gridTotal(), 28, y + 12);
            y += 20;
            for (int i = 0; i < page.gridTotal(); i++) {
                int cx = 28 + (i % 30) * 14;
                int cy = y + (i / 30) * 14;
                g.setColor(i < page.gridUsed() ? SLOT_ON : SLOT_OFF);
                g.fillRoundRect(cx, cy, 11, 11, 3, 3);
            }
            y += 14 * ((page.gridTotal() + 29) / 30) + 8;
        }

        if (page.columns() != null && page.columns().length > 0) {
            g.setFont(mono);
            FontMetrics fm = g.getFontMetrics();
            int icons = page.rowIcons().isEmpty() ? 0 : 26;
            // With two accounts in one table, whose row it is has to be
            // readable before the numbers are: the face goes in front of
            // everything, ahead of the time it happened.
            int faceGutter = page.rowFaces().isEmpty() ? 0 : 26;
            int n = page.columns().length;
            int[] widths = new int[n];
            for (int c = 0; c < n; c++) {
                widths[c] = fm.stringWidth(page.columns()[c]);
                for (String[] row : page.rows()) if (c < row.length) widths[c] = Math.max(widths[c], fm.stringWidth(row[c]));
            }
            int total = 0;
            for (int w : widths) total += w;
            // A column claims what its widest cell measures, which is fine
            // until one of them is "netherite upgrade smithing template x64":
            // the gap hits its floor and the last column is drawn off the edge
            // of the picture. The table cannot be wider than the page, so the
            // widest column gives up what it has to and its text is elided.
            int avail = WIDTH - 56 - icons - faceGutter - 16 * n;
            while (total > avail) {
                int widest = 0;
                for (int c = 1; c < n; c++) if (widths[c] > widths[widest]) widest = c;
                if (widths[widest] <= 48) break;   // nothing left worth taking
                widths[widest] -= 4;
                total -= 4;
            }
            int gap = Math.max(16, (WIDTH - 56 - icons - faceGutter - total) / Math.max(1, n));
            int iconColumn = Math.max(0, Math.min(n - 1, page.iconColumn()));
            int[] xs = new int[n];
            int x = 28 + faceGutter;
            for (int c = 0; c < n; c++) {
                // The picture sits directly in front of the column it belongs to.
                if (c == iconColumn) x += icons;
                xs[c] = x;
                x += widths[c] + gap;
            }
            g.setColor(GOLD);
            for (int c = 0; c < n; c++) drawCell(g, page.columns()[c], xs[c], widths[c], y + 20, page.rightAlign() != null && page.rightAlign()[c], fm);
            y += 28;
            g.setColor(LINE);
            g.drawLine(28, y, WIDTH - 28, y);
            y += 4;
            int i = 0;
            for (String[] row : page.rows()) {
                if (i % 2 == 1) {
                    g.setColor(new Color(32, 35, 45));
                    g.fillRect(24, y + 2, WIDTH - 48, 24);
                }
                if (faceGutter > 0 && i < page.rowFaces().size()) {
                    java.awt.image.BufferedImage head = Avatars.head(page.rowFaces().get(i));
                    if (head != null) g.drawImage(head, 28, y + 4, 20, 20, null);
                }
                if (icons > 0 && i < page.rowIcons().size()) {
                    java.awt.image.BufferedImage icon = ItemTextures.get(page.rowIcons().get(i));
                    if (icon != null) g.drawImage(icon, xs[iconColumn] - icons + 2, y + 3, 20, 20, null);
                }
                for (int c = 0; c < n && c < row.length; c++) {
                    String cell = row[c];
                    g.setColor(cellColor(cell));
                    drawCell(g, cell, xs[c], widths[c], y + 19, page.rightAlign() != null && page.rightAlign()[c], fm);
                }
                y += 26;
                i++;
            }
            if (page.rows().isEmpty()) {
                g.setColor(DIM);
                g.drawString("nothing to show", 28, y + 19);
                y += 26;
            }
        }

        g.setFont(small);
        g.setColor(DIM);
        g.drawString(page.footer(), 28, height - 22);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static void drawCell(Graphics2D g, String text, int x, int width, int y, boolean right, FontMetrics fm) {
        String shown = text;
        if (fm.stringWidth(shown) > width) {
            // The front of a name is what identifies it, so the tail goes.
            while (shown.length() > 1 && fm.stringWidth(shown + "…") > width) {
                shown = shown.substring(0, shown.length() - 1);
            }
            shown = shown + "…";
        }
        int tx = right ? x + width - fm.stringWidth(shown) : x;
        g.drawString(shown, tx, y);
    }

    private static Color cellColor(String cell) {
        if (cell.startsWith("+")) return GREEN;
        if (cell.startsWith("-") && cell.length() > 1 && Character.isDigit(cell.charAt(1))) return RED;
        return TEXT;
    }

    private static Color colorOf(String name) {
        if (name == null) return TEXT;
        return switch (name) {
            case "green" -> GREEN;
            case "red" -> RED;
            case "blue" -> BLUE;
            case "gold" -> GOLD;
            case "dim" -> DIM;
            default -> TEXT;
        };
    }
}

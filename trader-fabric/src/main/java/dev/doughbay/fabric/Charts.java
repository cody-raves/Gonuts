package dev.doughbay.fabric;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;
import java.util.Locale;

/**
 * Chart primitives drawn with filled rectangles, since Minecraft's GUI layer
 * has no path or stroke API.
 *
 * <p>Palette and mark rules follow the project's visualization guidance:
 * series colours are the first three categorical slots stepped for a dark
 * surface (validated for colour-vision separation against this panel), grid
 * and axis lines are recessive, marks are thin, and labels are selective —
 * end points and reference lines only, never a number on every sample.
 * Charts are single-series by construction, so identity never rests on colour
 * alone.
 */
final class Charts {

    // Categorical slots 1-3, dark-surface steps. Validated all-pairs against
    // the panel surface: worst normal-vision ΔE 20.9, worst CVD ΔE 9.4.
    static final int SERIES_BLUE = 0xFF3987E5;
    static final int SERIES_ORANGE = 0xFFD95926;
    static final int SERIES_AQUA = 0xFF199E70;

    // Chart chrome, dark mode.
    static final int GRID = 0xFF2C2C2A;
    static final int AXIS = 0xFF383835;
    static final int SPARK_BASELINE = 0x88383835;
    static final int MUTED_INK = 0xFF898781;
    static final int SECONDARY_INK = 0xFFC3C2B7;

    private Charts() {
    }

    /** One observation: a unit price at a moment. */
    record Point(long at, double value) {
    }

    /** A line in a tooltip: a colour key, a label, and the value it carries. */
    record TooltipRow(int keyColor, String label, String value) {
    }

    /**
     * A tooltip waiting to be drawn. Held until every panel has rendered so it
     * always lands on top rather than under whatever draws next.
     */
    static final class Pending {
        int x;
        int y;
        List<TooltipRow> rows;

        void clear() {
            rows = null;
        }
    }

    /**
     * Draws a tooltip near (x, y), flipped away from the edges it would
     * otherwise overflow. Values lead in bright ink; labels follow in muted
     * ink, keyed by a short stroke of the series colour.
     */
    static void drawTooltip(GuiGraphicsExtractor g, Font font, Pending pending,
                            int screenLeft, int screenTop, int screenRight, int screenBottom) {
        if (pending == null || pending.rows == null || pending.rows.isEmpty()) return;

        int lineHeight = 10;
        int width = 0;
        for (TooltipRow row : pending.rows) {
            width = Math.max(width, 10 + font.width(row.value()) + 6 + font.width(row.label()));
        }
        width += 10;
        int height = pending.rows.size() * lineHeight + 6;

        int tx = pending.x + 8;
        int ty = pending.y - height - 6;
        if (tx + width > screenRight - 4) tx = pending.x - width - 8;
        if (tx < screenLeft + 4) tx = screenLeft + 4;
        if (ty < screenTop + 4) ty = pending.y + 10;
        if (ty + height > screenBottom - 4) ty = screenBottom - height - 4;

        g.fill(tx, ty, tx + width, ty + height, 0xF0121218);
        g.fill(tx, ty, tx + width, ty + 1, AXIS);
        g.fill(tx, ty + height - 1, tx + width, ty + height, AXIS);
        g.fill(tx, ty, tx + 1, ty + height, AXIS);
        g.fill(tx + width - 1, ty, tx + width, ty + height, AXIS);

        int ry = ty + 4;
        for (TooltipRow row : pending.rows) {
            // A short stroke, not a filled box: at this density a box is
            // data-weight ink doing a label's job.
            g.fill(tx + 4, ry + 4, tx + 10, ry + 5, row.keyColor());
            g.text(font, row.value(), tx + 14, ry, 0xFFFFFFFF);
            g.text(font, row.label(), tx + 14 + font.width(row.value()) + 6, ry, MUTED_INK);
            ry += lineHeight;
        }
    }

    /**
     * Line chart of value over time with optional horizontal reference lines.
     * Returns the y below the chart.
     *
     * @param refs   reference levels drawn as dashed rules, e.g. quick-sale and median
     * @param marker a value highlighted on the y axis, e.g. the buy price; NaN to omit
     */
    static int line(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                    List<Point> points, int color, double[] refs, int[] refColors,
                    String[] refLabels, double marker, int markerColor,
                    long windowEnd, long windowSpan,
                    int mouseX, int mouseY, Pending pending) {
        if (w < 4 || h < 4) return y + Math.max(0, h) + 4;
        drawFrame(g, x, y, w, h);
        if (points == null || points.size() < 2) {
            g.text(font, "not enough history to plot", x + 6, y + h / 2 - 4, MUTED_INK);
            return y + h + 4;
        }

        long tEnd = windowEnd > 0 ? windowEnd : points.get(points.size() - 1).at();
        long span = Math.max(1, windowSpan);
        long tStart = tEnd - span;
        List<Point> visible = new java.util.ArrayList<>();
        for (Point point : points) {
            if (point.at() >= tStart && point.at() <= tEnd && Double.isFinite(point.value())) {
                visible.add(point);
            }
        }
        if (visible.size() < 2) {
            g.text(font, "not enough completed sales in this window",
                    x + 6, y + h / 2 - 4, MUTED_INK);
            return y + h + 4;
        }

        double[] safeRefs = refs == null ? new double[0] : refs;

        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (Point p : visible) {
            lo = Math.min(lo, p.value());
            hi = Math.max(hi, p.value());
        }
        for (double r : safeRefs) {
            if (!Double.isNaN(r)) {
                lo = Math.min(lo, r);
                hi = Math.max(hi, r);
            }
        }
        // The marker deliberately does NOT widen the range. A listing far below
        // market is the normal case here, and letting it set the scale would
        // squash the actual price movement into a flat line.
        // A flat series would divide by zero; give it a little room.
        if (hi - lo < 1e-6) {
            hi = lo + Math.max(1, Math.abs(lo) * 0.1);
        }
        double pad = (hi - lo) * 0.08;
        lo -= pad;
        hi += pad;

        double mid = (lo + hi) / 2.0;
        int labelWidth = Math.max(font.width(money(hi)),
                Math.max(font.width(money(mid)), font.width(money(lo))));
        int plotLeft = x + labelWidth + 7;
        int plotRight = x + w - 4;
        int plotTop = y + 3;
        int plotBottom = y + h - 13;
        int plotWidth = Math.max(4, plotRight - plotLeft + 1);
        int plotHeight = Math.max(4, plotBottom - plotTop + 1);

        // Three labelled y ticks are enough to establish the range without
        // turning the plot into a wall of text.  The labels live in a real
        // left gutter instead of sitting over the completed-sale series.
        double[] tickValues = {hi, mid, lo};
        for (double tickValue : tickValues) {
            int gy = valueToY(tickValue, lo, hi, plotTop, plotHeight);
            g.fill(plotLeft, gy, plotRight + 1, gy + 1, GRID);
            String tick = money(tickValue);
            int tickY = clamp(gy - 4, y + 1, y + h - 11);
            g.text(font, tick, plotLeft - font.width(tick) - 4, tickY, MUTED_INK);
        }

        // Time labels get their own baseline.  The detail chart is a fixed
        // twelve-hour view, so a gap with no sales remains visibly empty
        // instead of being stretched to look active.
        int timeY = y + h - 10;
        String startLabel = spanHoursLabel(span);
        String middleLabel = halfSpanLabel(span);
        String endLabel = "now";
        g.text(font, startLabel, plotLeft, timeY, MUTED_INK);
        g.text(font, middleLabel,
                plotLeft + (plotWidth - font.width(middleLabel)) / 2, timeY, MUTED_INK);
        g.text(font, endLabel, plotRight - font.width(endLabel) + 1, timeY, MUTED_INK);

        // Reference levels are deliberately drawn before the blue series and
        // carry no in-plot labels.  Their dashed shape and the separate legend
        // identify them without obscuring actual completed-sale movement.
        for (int i = 0; i < safeRefs.length; i++) {
            if (Double.isNaN(safeRefs[i])) continue;
            int refColor = colorAt(refColors, i, SERIES_ORANGE);
            int ry = valueToY(safeRefs[i], lo, hi, plotTop, plotHeight);
            dashedHorizontal(g, plotLeft, plotRight, ry, refColor, 4, 3);
        }

        // A bargain listing must not widen the range and flatten the history.
        // If it is off-scale, pin its dashed guide to the nearest plot edge;
        // the price band immediately below shows the full distance.
        if (!Double.isNaN(marker)) {
            int markerY = marker < lo ? plotBottom
                    : marker > hi ? plotTop
                    : valueToY(marker, lo, hi, plotTop, plotHeight);
            dashedHorizontal(g, plotLeft, plotRight, markerY, markerColor, 2, 3);
            if (marker < lo || marker > hi) {
                g.fill(plotLeft, markerY - 2, plotLeft + 3, markerY + 3, markerColor);
            }
        }

        // The series is painted last with a one-pixel logical stroke, keeping
        // it crisp at GUI scale 2 and always visible over dashed references.
        int prevX = -1;
        int prevY = -1;
        for (Point p : visible) {
            int px = timeToX(p.at(), tStart, tEnd, plotLeft, plotWidth);
            int py = valueToY(p.value(), lo, hi, plotTop, plotHeight);
            if (prevX >= 0) {
                thinSegment(g, prevX, prevY, px, py, color);
            }
            prevX = px;
            prevY = py;
        }
        if (prevX >= 0) {
            g.fill(prevX - 1, prevY - 1, prevX + 2, prevY + 2, color);
        }

        // Hover layer: a crosshair that snaps to the nearest sample, so the
        // reader aims at a moment in time rather than at a 2px line.
        if (pending != null && mouseX >= plotLeft && mouseX <= plotRight
                && mouseY >= plotTop && mouseY <= plotBottom) {
            int nearest = 0;
            int bestDistance = Integer.MAX_VALUE;
            for (int i = 0; i < visible.size(); i++) {
                int px = timeToX(visible.get(i).at(), tStart, tEnd, plotLeft, plotWidth);
                int distance = Math.abs(px - mouseX);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    nearest = i;
                }
            }
            Point hit = visible.get(nearest);
            int hx = timeToX(hit.at(), tStart, tEnd, plotLeft, plotWidth);
            int hy = valueToY(hit.value(), lo, hi, plotTop, plotHeight);

            g.fill(hx, plotTop, hx + 1, plotBottom + 1, AXIS);
            g.fill(hx - 2, hy - 2, hx + 3, hy + 3, color);

            List<TooltipRow> rows = new java.util.ArrayList<>();
            rows.add(new TooltipRow(color, ago(tEnd - hit.at()), money(hit.value())));
            for (int i = 0; i < safeRefs.length; i++) {
                if (Double.isNaN(safeRefs[i])) continue;
                String label = refLabels != null && i < refLabels.length && refLabels[i] != null
                        ? refLabels[i] : "level";
                rows.add(new TooltipRow(colorAt(refColors, i, SERIES_ORANGE), label, money(safeRefs[i])));
            }
            if (!Double.isNaN(marker)) {
                rows.add(new TooltipRow(markerColor, "this listing", money(marker)));
            }
            pending.x = hx;
            pending.y = hy;
            pending.rows = rows;
        }

        // Paint the outline last so marks and reference lines cannot leave a
        // soft or broken edge around the chart card.
        drawFrame(g, x, y, w, h);
        return y + h + 4;
    }

    /** Volume bars: how many sales landed in each time bucket. */
    static int bars(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h,
                    int[] counts, int color, String caption,
                    int mouseX, int mouseY, Pending pending, long spanMillis) {
        if (w < 4 || h < 4) return y + Math.max(0, h) + 4;
        drawFrame(g, x, y, w, h);
        int[] safeCounts = counts == null ? new int[0] : counts;
        int max = 1;
        for (int c : safeCounts) max = Math.max(max, Math.max(0, c));
        int n = Math.max(1, safeCounts.length);
        int mid = (max + 1) / 2;
        int axisWidth = Math.max(font.width(Integer.toString(max)),
                Math.max(font.width(Integer.toString(mid)), font.width("0")));
        int plotLeft = x + 4;
        int plotRight = x + w - axisWidth - 7;
        int plotTop = y + 12;
        int plotBottom = y + h - 3;
        int plotWidth = Math.max(2, plotRight - plotLeft);
        int availableHeight = Math.max(1, plotBottom - plotTop);

        // A sparse count axis makes the histogram comparable at a glance.
        // The bars themselves remain thin even on a wide monitor, preserving
        // the visual rhythm of twenty-four real 30-minute buckets.
        int[] axisValues = {max, mid, 0};
        for (int axisValue : axisValues) {
            int gy = plotBottom - (int) Math.round((double) axisValue / max * availableHeight);
            g.fill(plotLeft, gy, plotRight + 1, gy + 1, GRID);
            String label = Integer.toString(axisValue);
            g.text(font, label, plotRight + 4,
                    clamp(gy - 4, y + 1, y + h - 10), MUTED_INK);
        }

        for (int i = 0; i < safeCounts.length; i++) {
            double slotLeft = plotLeft + (double) i * plotWidth / n;
            double slotRight = plotLeft + (double) (i + 1) * plotWidth / n;
            int slotX = (int) Math.floor(slotLeft);
            int slotEnd = Math.max(slotX + 1, (int) Math.floor(slotRight));
            int barW = Math.max(1, Math.min(5, slotEnd - slotX - 2));
            int bx = slotX + Math.max(0, (slotEnd - slotX - barW) / 2);
            int count = Math.max(0, safeCounts[i]);
            int barH = clamp((int) ((double) count / max * availableHeight), 0, availableHeight);
            // The hit target is the whole column, not just the painted bar, so
            // an empty or tiny bucket is still hoverable.
            boolean hovered = pending != null && mouseX >= slotX && mouseX < slotEnd
                    && mouseY >= plotTop && mouseY <= plotBottom;
            if (barH > 0) {
                g.fill(bx, plotBottom - barH, bx + barW, plotBottom,
                        hovered ? lift(color) : color);
            }
            if (hovered) {
                long bucketAgo = (long) (((safeCounts.length - i) - 0.5)
                        / safeCounts.length * spanMillis);
                pending.x = bx + barW / 2;
                pending.y = plotBottom - Math.max(barH, 4);
                pending.rows = List.of(
                        new TooltipRow(color, ago(bucketAgo), count + " sales"));
            }
        }
        g.text(font, caption, x + 3, y + 2, MUTED_INK);
        drawFrame(g, x, y, w, h);
        return y + h + 4;
    }

    /**
     * Compact trend with no axes or labels, for table rows. Rendered as a
     * filled area so it reads at ~8px tall where a hairline would not.
     */
    static void sparkline(GuiGraphicsExtractor g, int x, int y, int w, int h,
                          List<Point> points, int color) {
        if (w < 3 || h < 3 || points == null || points.size() < 2) return;
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (Point p : points) {
            lo = Math.min(lo, p.value());
            hi = Math.max(hi, p.value());
        }
        if (hi - lo < 1e-6) hi = lo + 1;

        int baselineY = y + h - 1;
        int areaColor = withAlpha(color, 0x2E);
        int sampleCount = Math.max(2, Math.min(points.size(), Math.max(2, w - 1)));
        int prevX = -1;
        int prevY = -1;
        for (int sample = 0; sample < sampleCount; sample++) {
            double progress = (double) sample / (sampleCount - 1);
            int pointIndex = clamp((int) Math.round(progress * (points.size() - 1)), 0, points.size() - 1);
            int px = x + (int) Math.round(progress * (w - 2));
            int py = sparkValueToY(points.get(pointIndex).value(), lo, hi, y, h);
            if (prevX >= 0) {
                fillAreaSegment(g, prevX, prevY, px, py, baselineY, areaColor);
                segment(g, prevX, prevY, px, py, color);
            }
            prevX = px;
            prevY = py;
        }
        g.fill(x, baselineY, x + w, baselineY + 1, SPARK_BASELINE);
        if (prevX >= 0) {
            g.fill(prevX, prevY, Math.min(x + w, prevX + 2), Math.min(y + h, prevY + 2), color);
        }
    }

    /**
     * Horizontal depth bars for the active order book: how much supply sits at
     * each price level, cheapest first.
     */
    static int depth(GuiGraphicsExtractor g, Font font, int x, int y, int w, int rowHeight,
                     List<double[]> levels, int color, double markerPrice) {
        if (w <= 90 || rowHeight < 3 || levels == null || levels.isEmpty()) return y;
        double maxQty = 1;
        for (double[] level : levels) {
            if (level != null && level.length >= 2 && Double.isFinite(level[1])) {
                maxQty = Math.max(maxQty, Math.max(0, level[1]));
            }
        }
        int yy = y;
        for (double[] level : levels) {
            if (level == null || level.length < 2 || !Double.isFinite(level[0]) || !Double.isFinite(level[1])) {
                continue;
            }
            int barW = clamp((int) (Math.max(0, level[1]) / maxQty * (w - 90)), 1, w - 90);
            boolean isMarker = Math.abs(level[0] - markerPrice) < 1e-6;
            g.fill(x + 70, yy, x + 70 + barW, yy + rowHeight - 2,
                    isMarker ? SERIES_AQUA : color);
            g.text(font, money(level[0]), x, yy, isMarker ? SERIES_AQUA : SECONDARY_INK);
            yy += rowHeight;
        }
        return yy;
    }

    // ------------------------------------------------------------------ helpers

    private static int valueToY(double value, double lo, double hi, int y, int h) {
        double t = (value - lo) / (hi - lo);
        t = Math.max(0, Math.min(1, t));
        return clamp(y + h - 2 - (int) Math.round(t * (h - 4)), y + 2, y + h - 2);
    }

    private static int sparkValueToY(double value, double lo, double hi, int y, int h) {
        double t = (value - lo) / (hi - lo);
        t = Math.max(0, Math.min(1, t));
        return clamp(y + h - 2 - (int) Math.round(t * (h - 3)), y, y + h - 2);
    }

    private static int timeToX(long at, long start, long end, int x, int w) {
        double t = (double) (at - start) / Math.max(1, end - start);
        t = Math.max(0, Math.min(1, t));
        return clamp(x + 1 + (int) Math.round(t * (w - 3)), x + 1, x + w - 2);
    }

    private static void drawFrame(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) return;
        g.fill(x, y, x + w, y + 1, AXIS);
        g.fill(x, y + h - 1, x + w, y + h, AXIS);
        g.fill(x, y, x + 1, y + h, AXIS);
        g.fill(x + w - 1, y, x + w, y + h, AXIS);
    }

    /** A horizontal reference whose gaps remain visible at every GUI scale. */
    private static void dashedHorizontal(GuiGraphicsExtractor g, int left, int right,
                                         int y, int color, int dash, int gap) {
        int step = Math.max(2, dash + gap);
        for (int xx = left; xx <= right; xx += step) {
            g.fill(xx, y, Math.min(right + 1, xx + Math.max(1, dash)), y + 1, color);
        }
    }

    /** A translucent polygon approximation using at most one column per x. */
    private static void fillAreaSegment(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1,
                                        int baselineY, int color) {
        int from = Math.min(x0, x1);
        int to = Math.max(x0, x1);
        for (int px = from; px <= to; px++) {
            double t = (double) (px - x0) / (x1 - x0 == 0 ? 1 : x1 - x0);
            int lineY = (int) Math.round(y0 + (y1 - y0) * t);
            int fillTop = Math.min(lineY + 1, baselineY);
            if (fillTop < baselineY) g.fill(px, fillTop, px + 1, baselineY, color);
        }
    }

    /** Bresenham-style segment, drawn as 2px blocks so it reads on a dark panel. */
    private static void segment(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int guard = 0;
        while (guard++ < 4096) {
            g.fill(x0, y0, x0 + 2, y0 + 2, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = err << 1;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    /** A crisp one-logical-pixel line for the full-size history plot. */
    private static void thinSegment(GuiGraphicsExtractor g, int x0, int y0,
                                    int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int guard = 0;
        while (guard++ < 4096) {
            g.fill(x0, y0, x0 + 1, y0 + 1, color);
            if (x0 == x1 && y0 == y1) break;
            int e2 = err << 1;
            if (e2 > -dy) {
                err -= dy;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    /** Lightens a mark so a hovered bar visibly responds. */
    private static int lift(int argb) {
        int a = argb >>> 24;
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 40);
        int gg = Math.min(255, ((argb >> 8) & 0xFF) + 40);
        int b = Math.min(255, (argb & 0xFF) + 40);
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }

    private static int withAlpha(int argb, int alpha) {
        return (clamp(alpha, 0, 255) << 24) | (argb & 0x00FFFFFF);
    }

    private static int colorAt(int[] colors, int index, int fallback) {
        return colors != null && index >= 0 && index < colors.length ? colors[index] : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** "12m ago" style relative time for tooltips. */
    private static String ago(long millis) {
        long minutes = Math.max(0, millis) / 60_000;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 48) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    private static String spanLabel(long millis) {
        long minutes = millis / 60_000;
        if (minutes < 90) return "last " + minutes + "m";
        long hours = minutes / 60;
        if (hours < 48) return "last " + hours + "h";
        return "last " + (hours / 24) + "d";
    }

    private static String spanHoursLabel(long millis) {
        long minutes = Math.max(1, millis / 60_000);
        if (minutes < 60) return minutes + "m ago";
        long hours = Math.max(1, Math.round(minutes / 60.0));
        return hours < 48 ? hours + "h ago" : Math.max(1, Math.round(hours / 24.0)) + "d ago";
    }

    private static String halfSpanLabel(long millis) {
        long minutes = Math.max(1, millis / 120_000);
        if (minutes < 60) return minutes + "m ago";
        long hours = Math.max(1, Math.round(minutes / 60.0));
        return hours < 48 ? hours + "h ago" : Math.max(1, Math.round(hours / 24.0)) + "d ago";
    }

    private static String money(double value) {
        if (!Double.isFinite(value)) return "$--";
        if (Math.abs(value) >= 1_000_000) return String.format(Locale.ROOT, "$%.2fm", value / 1e6);
        if (Math.abs(value) >= 1_000) return String.format(Locale.ROOT, "$%.1fk", value / 1e3);
        return String.format(Locale.ROOT, "$%.0f", value);
    }
}

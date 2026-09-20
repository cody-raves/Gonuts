package dev.doughbay.cli;

import java.util.List;
import java.util.Locale;

/** Minimal terminal chart for the simulated-balance-over-time graph. */
final class AsciiChart {

    private AsciiChart() {
    }

    static String render(List<Long> values, int width, int height) {
        if (values.isEmpty()) return "(no data)\n";
        List<Long> sampled = resample(values, width);
        long min = sampled.stream().mapToLong(Long::longValue).min().orElse(0);
        long max = sampled.stream().mapToLong(Long::longValue).max().orElse(1);
        if (max == min) max = min + 1;

        StringBuilder sb = new StringBuilder();
        for (int row = height - 1; row >= 0; row--) {
            double rowValue = min + (max - min) * (row + 0.5) / height;
            sb.append(String.format(Locale.ROOT, "%12s |",
                    row == height - 1 ? format(max) : (row == 0 ? format(min) : "")));
            for (long v : sampled) {
                double filled = (double) (v - min) / (max - min) * height;
                sb.append(filled >= row + 0.5 ? '█' : (filled >= row ? '▄' : ' '));
            }
            sb.append('\n');
            if (rowValue < min) break;
        }
        sb.append(" ".repeat(13)).append('+').append("-".repeat(sampled.size())).append('\n');
        return sb.toString();
    }

    private static List<Long> resample(List<Long> values, int width) {
        if (values.size() <= width) return values;
        java.util.ArrayList<Long> out = new java.util.ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            out.add(values.get((int) ((long) i * values.size() / width)));
        }
        return out;
    }

    private static String format(long v) {
        if (Math.abs(v) >= 1_000_000) return String.format(Locale.ROOT, "%.2fm", v / 1e6);
        if (Math.abs(v) >= 1_000) return String.format(Locale.ROOT, "%.1fk", v / 1e3);
        return Long.toString(v);
    }
}

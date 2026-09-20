package dev.doughbay.core.performance;

import java.util.Locale;

/** Account whose positions are included in a performance report. */
public enum PerformanceMode {
    PAPER,
    REAL;

    /** Strict, locale-independent parsing for persistence and UI boundaries. */
    public static PerformanceMode parse(String value) {
        if (value == null) throw new IllegalArgumentException("performance mode is required");
        return valueOf(value.strip().toUpperCase(Locale.ROOT));
    }

    public boolean matches(String value) {
        if (value == null) return false;
        return name().equals(value.strip().toUpperCase(Locale.ROOT));
    }
}

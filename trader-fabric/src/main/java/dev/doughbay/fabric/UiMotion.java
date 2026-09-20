package dev.doughbay.fabric;

/** Time-based presentation math, independent of frame rate and Minecraft. */
final class UiMotion {
    private UiMotion() { }

    static double clamp01(double value) { return Math.max(0, Math.min(1, value)); }

    static double easeOut(double value) {
        double t = 1 - clamp01(value);
        return 1 - t * t * t;
    }

    static double visibility(long age, long duration, long enter, long exit) {
        if (age < 0 || age >= duration) return 0;
        return Math.min(easeOut(age / (double) enter), clamp01((duration - age) / (double) exit));
    }

    static int boundedWidth(int wanted, int minimum, int maximum, int screen, int margin) {
        return Math.max(1, Math.min(Math.max(1, screen - margin * 2),
                Math.max(minimum, Math.min(maximum, wanted))));
    }
}

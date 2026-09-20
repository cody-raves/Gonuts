package dev.doughbay.fabric;

/**
 * Goes quiet when the server stops answering.
 *
 * <p>Every part of this bot answers an unanswered action by sending another
 * one. The sell command has two retries, the own-listings click has two, the
 * order navigation has two, payroll carries an unpaid amount into the next
 * payment and tries again. Each of those is reasonable alone. Together, on a
 * server that has stopped replying, they are a bot that sends its hardest at
 * the exact moment the server can cope least.
 *
 * <p>On 2026-09-07 at 16:05 that ended the way it was always going to. The
 * backend went into an update, sells stopped getting a confirmation GUI and
 * payroll stopped getting receipts; every subsystem retried on its own clock;
 * and the server closed the connection with "You are sending too many large
 * packets :(". The restart notice and the kick arrived in the same second, so
 * the mod's existing restart handling never had a chance to help - by the time
 * a server says it is going down, the damage is done.
 *
 * <p>Repeated timeouts are the earlier signal, and they need no announcement.
 * A healthy server fails an operation now and then; it does not fail three in
 * a row. So failures are counted here across every subsystem, and after a few
 * the whole bot holds still for a while rather than each part deciding
 * separately to try once more. Any success clears it, because one answer means
 * the server is listening again.
 */
public final class ServerStrain {
    /** Consecutive failures before anything is held back. */
    private static final int PATIENCE = 3;
    /** How long the hold lasts, by how many failures have piled up. */
    private static final long[] QUIET_MILLIS = {30_000L, 60_000L, 120_000L};
    /** A failure this old is not part of the current run of them. */
    private static final long RUN_WINDOW_MILLIS = 90_000L;

    private static volatile int consecutive;
    private static volatile long lastFailureAt;
    private static volatile long quietUntil;
    private static volatile String reason = "";

    private ServerStrain() {
    }

    /**
     * An operation ended without the server answering. Counted only while the
     * failures are close together: two unrelated ones an hour apart are a
     * normal day, not a struggling server.
     */
    public static synchronized void noteFailure(String detail) {
        long now = System.currentTimeMillis();
        if (now - lastFailureAt > RUN_WINDOW_MILLIS) consecutive = 0;
        lastFailureAt = now;
        consecutive++;
        if (consecutive < PATIENCE) return;
        int step = Math.min(consecutive - PATIENCE, QUIET_MILLIS.length - 1);
        long until = now + QUIET_MILLIS[step];
        if (until <= quietUntil) return;
        quietUntil = until;
        reason = detail == null ? "" : detail;
        DoughBayClient.LOGGER.warn(
                "DoughBay: {} operations in a row went unanswered; holding everything for {} s ({})",
                consecutive, QUIET_MILLIS[step] / 1000, reason);
    }

    /** The server answered: whatever was wrong is over. */
    public static synchronized void noteSuccess() {
        if (consecutive == 0 && quietUntil == 0) return;
        if (quietUntil > 0) {
            DoughBayClient.LOGGER.info("DoughBay: the server answered again; carrying on");
        }
        consecutive = 0;
        quietUntil = 0;
        reason = "";
    }

    /** Whether everything should stay still right now. */
    public static boolean holding() {
        return System.currentTimeMillis() < quietUntil;
    }

    /** Command dispatch must respect the same hold as the higher-level desks. */
    public static long commandNotBeforeMillis() { return quietUntil; }

    /**
     * How much to widen command and click spacing right now; 1.0 when the
     * server is answering normally. It rises with a run of unanswered
     * operations so the bot eases off <em>before</em> it trips the full hold,
     * because under strain a click that lands is worth far more than one sent
     * sooner and dropped - which is exactly what leaves a read stalled on a
     * page that never advanced. Capped so pacing stays sane.
     */
    public static double pacingMultiplier() {
        if (System.currentTimeMillis() - lastFailureAt > RUN_WINDOW_MILLIS) return 1.0;
        int c = consecutive;
        if (c <= 0) return 1.0;
        return Math.min(1.0 + 0.5 * c, 3.0);
    }

    /** Seconds left of the hold, for the status line; 0 when not holding. */
    public static long secondsLeft() {
        long left = quietUntil - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /** "the server has not answered 4 operations; waiting 45 s", or "". */
    public static String describe() {
        if (!holding()) return "";
        return "the server has not answered " + consecutive
                + " operations; waiting " + secondsLeft() + " s";
    }

    /** Forgets everything; a fresh connection starts with no history. */
    public static synchronized void reset() {
        consecutive = 0;
        quietUntil = 0;
        lastFailureAt = 0;
        reason = "";
    }
}

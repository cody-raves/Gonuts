package dev.doughbay.fabric;

/** Checks the command's actual handoff to Minecraft's connection, not server receipt. */
public final class CommandSendProbe {
    private static final ThreadLocal<Attempt> CURRENT = new ThreadLocal<>();

    private static final class Attempt {
        String observed;
    }

    private CommandSendProbe() { }

    static void verify(String expected, Runnable send) {
        Attempt previous = CURRENT.get();
        Attempt attempt = new Attempt();
        CURRENT.set(attempt);
        try {
            send.run();
            if (attempt.observed == null) {
                throw new IllegalStateException("Command /" + expected
                        + " was canceled locally before reaching the connection; no confirmation can arrive");
            }
            if (!expected.equals(attempt.observed)) {
                throw new IllegalStateException("Command changed before reaching the connection; inspect client command handlers");
            }
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    /** Called only for outgoing command packets; manual chat is never retained. */
    public static void observe(String command) {
        Attempt attempt = CURRENT.get();
        if (attempt != null) attempt.observed = command;
    }
}

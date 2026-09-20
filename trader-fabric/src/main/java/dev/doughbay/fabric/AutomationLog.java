package dev.doughbay.fabric;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A small in-memory tail of the automation's own status line, for the Logs tab.
 *
 * <p>The session already logs every state transition to the game log; that is
 * hard to read in game and impossible on a machine you are not sitting at. This
 * keeps the last couple of hundred of those lines with a timestamp so the Logs
 * tab can show, at a glance, what the bot is doing and - when it pauses - why it
 * stopped and what it is waiting on.
 */
public final class AutomationLog {
    /** One captured status line: when it happened, the state, and the message. */
    public record Line(long at, String state, String text) {
    }

    private static final int MAX = 250;
    private static final ArrayDeque<Line> LINES = new ArrayDeque<>();

    private AutomationLog() {
    }

    /** Records a status line; called once per transition from the session tick. */
    public static synchronized void add(String state, String text) {
        if (text == null) return;
        Line last = LINES.peekLast();
        if (last != null && last.state().equals(state) && last.text().equals(text)) return;
        LINES.addLast(new Line(System.currentTimeMillis(), state, text));
        while (LINES.size() > MAX) LINES.removeFirst();
    }

    /** The captured lines, oldest first. */
    public static synchronized List<Line> recent() {
        return new ArrayList<>(LINES);
    }

    public static synchronized void clear() {
        LINES.clear();
    }
}

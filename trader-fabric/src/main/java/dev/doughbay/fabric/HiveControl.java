package dev.doughbay.fabric;

import dev.doughbay.core.automation.ContinuousAutomationPolicy;
import dev.doughbay.core.execution.ExecutionResult;
import dev.doughbay.fabric.automation.AutomationSessionController;
import dev.doughbay.storage.Database;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Start, resume and stop for every client in the hive, from wherever the
 * panel happens to be hosted.
 *
 * <p>Only one client publishes the Discord panel, and its Start and Stop
 * buttons reach that client's own session and nobody else's. With two
 * accounts trading that left the other one out of reach: the operator could
 * see it idle and could not touch it. The clients already share a ledger, so
 * an order for another client goes through it - a row naming the target and
 * the command, which the target picks up on its next tick, carries out on its
 * own thread, and answers with the result. The panel reads the answer back.
 *
 * <p>Orders age out in two minutes. A client that was not there to take one
 * should not carry it out an hour later when it comes back.
 */
public final class HiveControl {

    public static final String START = "START";
    public static final String STOP = "STOP";
    private static final long ORDER_TTL_MILLIS = 120_000L;
    private static final long POLL_MILLIS = 2_000L;

    private final Path databasePath;
    private long polledAt;

    public HiveControl(Path databasePath) {
        this.databasePath = databasePath;
    }

    /** What a client did with an order: who it was for, what it was, and how it went. */
    public record Result(long id, String target, String command, String result, long doneAt) {}

    /** Posts an order for another client; false when it could not be written. */
    public boolean post(String target, String command, String by) {
        if (target == null || target.isBlank() || command == null || command.isBlank()) return false;
        try (Database db = new Database(databasePath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "INSERT INTO hive_control (target, command, issued_by, issued_at, status) VALUES (?,?,?,?,'OPEN')")) {
            ps.setString(1, target);
            ps.setString(2, command);
            ps.setString(3, by == null ? "" : by);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
            DoughBayClient.LOGGER.info("DoughBay hive: sent {} to {}", command, target);
            return true;
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay hive could not post an order: {}", e.toString());
            return false;
        }
    }

    /**
     * Carries out any order addressed to this client. Called from the client
     * tick, so the session is driven from the same thread the panel's own
     * buttons use.
     */
    public void poll(AutomationSessionController session) {
        if (session == null || !DoughBayClient.multiClient()) return;
        long now = System.currentTimeMillis();
        if (now - polledAt < POLL_MILLIS) return;
        polledAt = now;
        String me = DoughBayClient.account();
        if (me.isBlank()) return;
        List<Object[]> orders = new ArrayList<>();
        try (Database db = new Database(databasePath)) {
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "UPDATE hive_control SET status='EXPIRED', done_at=? WHERE status='OPEN' AND issued_at < ?")) {
                ps.setLong(1, now);
                ps.setLong(2, now - ORDER_TTL_MILLIS);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT id, command, issued_by FROM hive_control WHERE status='OPEN' AND lower(target)=lower(?) ORDER BY id")) {
                ps.setString(1, me);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) orders.add(new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3)});
                }
            }
        } catch (Exception e) {
            return;   // the ledger is busy; the order is still there next time
        }
        for (Object[] o : orders) {
            long id = (Long) o[0];
            String command = (String) o[1];
            String result;
            try {
                result = apply(session, command);
            } catch (RuntimeException e) {
                result = "Failed: " + e.getMessage();
            }
            DoughBayClient.LOGGER.info("DoughBay hive: {} from {} -> {}", command, o[2], result);
            try (Database db = new Database(databasePath);
                 PreparedStatement ps = db.connection().prepareStatement(
                         "UPDATE hive_control SET status='DONE', result=?, done_at=? WHERE id=?")) {
                ps.setString(1, result);
                ps.setLong(2, now);
                ps.setLong(3, id);
                ps.executeUpdate();
            } catch (Exception ignored) {
                // the order ran; the reply is the only thing lost
            }
        }
    }

    /**
     * The one place the panel's Start, Resume and Stop are decided, for this
     * client's own session and for orders from another client alike.
     */
    public static String apply(AutomationSessionController session, String command) {
        AutomationSessionController.SessionSnapshot s = session.snapshot();
        if (STOP.equals(command)) {
            if (s.state() == AutomationSessionController.State.STOPPED) return "Already stopped";
            session.stop();
            return "Stopped by operator";
        }
        if (!START.equals(command)) return "Unknown order: " + command;
        ContinuousAutomationPolicy policy = DoughBayClient.continuousPolicy();
        ExecutionResult result;
        if (s.state() == AutomationSessionController.State.PAUSED) {
            result = s.recoveredSession() || session.recoverableInHand()
                    ? session.resumeRecoveredSession(policy) : session.resume();
            // An in-place pause and a recovered one answer to different
            // calls; whichever was tried first, the other is worth a go.
            if (!result.ok()) {
                ExecutionResult other = s.recoveredSession() || session.recoverableInHand()
                        ? session.resume() : session.resumeRecoveredSession(policy);
                if (other.ok()) result = other;
            }
        } else if (s.state() != AutomationSessionController.State.STOPPED) {
            return "Already running (" + s.state() + ")";
        } else {
            String blocker = DoughBayClient.continuousPolicyBlocker();
            if (blocker != null && !blocker.isBlank()) return "Cannot start: " + blocker;
            result = session.start(policy, AutomationSessionController.RunMode.CONTINUOUS);
        }
        return (result.ok() ? "OK: " : "Refused: ") + result.detail();
    }

    /** Replies that came in after {@code since}, oldest first. */
    public List<Result> repliesSince(long since) {
        List<Result> out = new ArrayList<>();
        try (Database db = new Database(databasePath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "SELECT id, target, command, result, done_at FROM hive_control "
                             + "WHERE status='DONE' AND done_at > ? ORDER BY done_at LIMIT 10")) {
            ps.setLong(1, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Result(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)));
                }
            }
        } catch (Exception ignored) {
            // nothing to show this time round
        }
        return out;
    }

    /** The last state another client checkpointed, or "" when it never has. */
    public String stateOf(String client) {
        if (client == null || client.isBlank()) return "";
        try (Database db = new Database(databasePath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "SELECT controller_state FROM automation_session_state WHERE lower(client)=lower(?) "
                             + "ORDER BY updated_at DESC LIMIT 1")) {
            ps.setString(1, client);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        } catch (Exception e) {
            return "";
        }
    }
}

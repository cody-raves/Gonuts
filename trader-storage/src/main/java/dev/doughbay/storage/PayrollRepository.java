package dev.doughbay.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Payroll: who gets what share of the profit, when, and every payment made
 * under those rules. Rules live here so they survive restarts and rebuilds;
 * a payment row is written before the command is sent, so a crash between
 * the two can never pay twice.
 */
public final class PayrollRepository {
    /** One recipient. Percent is of the profit made since the rule's last payout. */
    public record Rule(long ruleId, String name, double percent, String triggerKind, double triggerValue,
                       boolean onlyOnline, long reserve, long dailyCap, boolean paused, boolean confirmed,
                       long createdAt, long lastPaidAt, long totalPaid) {
    }

    /** One {@code /pay}: SENT until the chat receipt confirms it, then PAID or FAILED. */
    public record Payment(long paymentId, long ruleId, String name, long amount, long windowFrom, long windowTo,
                          long profit, long requestedAt, long verifiedAt, String status, String note) {
    }

    private final Database db;

    public PayrollRepository(Database db) {
        this.db = db;
    }

    public List<Rule> rules() throws SQLException {
        List<Rule> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT rule_id, name, percent, trigger_kind, trigger_value, only_online, reserve, daily_cap, paused, "
                        + "confirmed, created_at, last_paid_at, total_paid FROM payroll_rules ORDER BY created_at");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Rule(rs.getLong(1), rs.getString(2), rs.getDouble(3), rs.getString(4), rs.getDouble(5),
                        rs.getInt(6) != 0, rs.getLong(7), rs.getLong(8), rs.getInt(9) != 0, rs.getInt(10) != 0,
                        rs.getLong(11), rs.getLong(12), rs.getLong(13)));
            }
        }
        return out;
    }

    public void save(Rule r) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT OR REPLACE INTO payroll_rules (rule_id, name, percent, trigger_kind, trigger_value, only_online, "
                        + "reserve, daily_cap, paused, confirmed, created_at, last_paid_at, total_paid) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1, r.ruleId());
            ps.setString(2, r.name());
            ps.setDouble(3, r.percent());
            ps.setString(4, r.triggerKind());
            ps.setDouble(5, r.triggerValue());
            ps.setInt(6, r.onlyOnline() ? 1 : 0);
            ps.setLong(7, r.reserve());
            ps.setLong(8, r.dailyCap());
            ps.setInt(9, r.paused() ? 1 : 0);
            ps.setInt(10, r.confirmed() ? 1 : 0);
            ps.setLong(11, r.createdAt());
            ps.setLong(12, r.lastPaidAt());
            ps.setLong(13, r.totalPaid());
            ps.executeUpdate();
        }
    }

    public void delete(long ruleId) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM payroll_rules WHERE rule_id = ?")) {
            ps.setLong(1, ruleId);
            ps.executeUpdate();
        }
    }

    public void setPaused(long ruleId, boolean paused) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement("UPDATE payroll_rules SET paused = ? WHERE rule_id = ?")) {
            ps.setInt(1, paused ? 1 : 0);
            ps.setLong(2, ruleId);
            ps.executeUpdate();
        }
    }

    public void setConfirmed(long ruleId, boolean confirmed) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement("UPDATE payroll_rules SET confirmed = ? WHERE rule_id = ?")) {
            ps.setInt(1, confirmed ? 1 : 0);
            ps.setLong(2, ruleId);
            ps.executeUpdate();
        }
    }

    /** Realized profit of REAL positions sold in the window (from, to]. */
    public long profitSince(long from, long to) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT COALESCE(SUM(realized_profit), 0) FROM positions WHERE mode = 'REAL' AND status = 'SOLD' "
                        + "AND closed_at > ? AND closed_at <= ?")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.round(rs.getDouble(1)) : 0;
            }
        }
    }

    public int salesSince(long from, long to) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT COUNT(*) FROM positions WHERE mode = 'REAL' AND status = 'SOLD' AND closed_at > ? AND closed_at <= ?")) {
            ps.setLong(1, from);
            ps.setLong(2, to);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** What a rule has paid (or sent without a receipt) since a moment. */
    public long paidSince(long ruleId, long since) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT COALESCE(SUM(amount), 0) FROM payroll_payments WHERE rule_id = ? AND requested_at > ? AND status <> 'FAILED'")) {
            ps.setLong(1, ruleId);
            ps.setLong(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** Writes the payment as SENT and returns it; the command goes out only after this row is durable. */
    public Payment recordPayment(Rule rule, long amount, long windowFrom, long windowTo, long profit, long now)
            throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO payroll_payments (rule_id, name, amount, profit_window_from, profit_window_to, profit, "
                        + "requested_at, verified_at, status, note) VALUES (?,?,?,?,?,?,?,0,'SENT','')",
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, rule.ruleId());
            ps.setString(2, rule.name());
            ps.setLong(3, amount);
            ps.setLong(4, windowFrom);
            ps.setLong(5, windowTo);
            ps.setLong(6, profit);
            ps.setLong(7, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                long id = keys.next() ? keys.getLong(1) : 0;
                return new Payment(id, rule.ruleId(), rule.name(), amount, windowFrom, windowTo, profit, now, 0, "SENT", "");
            }
        }
    }

    public void settle(long paymentId, String status, long verifiedAt, String note) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE payroll_payments SET status = ?, verified_at = ?, note = ? WHERE payment_id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, verifiedAt);
            ps.setString(3, note == null ? "" : note);
            ps.setLong(4, paymentId);
            ps.executeUpdate();
        }
    }

    /** A payout went out: the profit window closes here and the running total grows. */
    public void markPaid(long ruleId, long paidAt, long amount) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE payroll_rules SET last_paid_at = ?, total_paid = total_paid + ? WHERE rule_id = ?")) {
            ps.setLong(1, paidAt);
            ps.setLong(2, amount);
            ps.setLong(3, ruleId);
            ps.executeUpdate();
        }
    }

    /** A payment the player made by hand to a rule's name: counted in the total, the profit window untouched. */
    public void recordManual(Rule rule, long amount, long now, String note) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO payroll_payments (rule_id, name, amount, profit_window_from, profit_window_to, profit, "
                        + "requested_at, verified_at, status, note) VALUES (?,?,?,0,0,0,?,?,'MANUAL',?)")) {
            ps.setLong(1, rule.ruleId());
            ps.setString(2, rule.name());
            ps.setLong(3, amount);
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.setString(6, note == null ? "" : note);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE payroll_rules SET total_paid = total_paid + ? WHERE rule_id = ?")) {
            ps.setLong(1, amount);
            ps.setLong(2, rule.ruleId());
            ps.executeUpdate();
        }
    }

    /** Payments that never went out: the server dropped the command, so the money is still owed. */
    public long unpaidSum(long ruleId) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT COALESCE(SUM(amount), 0) FROM payroll_payments WHERE rule_id = ? AND status = 'UNPAID'")) {
            ps.setLong(1, ruleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** The owed amounts have been folded into one new payment; the old rows say where they went. */
    public void markCarried(long ruleId, long intoPaymentId, long at) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE payroll_payments SET status = 'CARRIED', verified_at = ?, note = ? "
                        + "WHERE rule_id = ? AND status = 'UNPAID'")) {
            ps.setLong(1, at);
            ps.setString(2, "carried into payment #" + intoPaymentId);
            ps.setLong(3, ruleId);
            ps.executeUpdate();
        }
    }

    /** The window closed with nothing to pay; the next one starts now. */
    public void touchPaid(long ruleId, long at) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE payroll_rules SET last_paid_at = ? WHERE rule_id = ?")) {
            ps.setLong(1, at);
            ps.setLong(2, ruleId);
            ps.executeUpdate();
        }
    }

    public List<Payment> recentPayments(int limit) throws SQLException {
        List<Payment> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT payment_id, rule_id, name, amount, profit_window_from, profit_window_to, profit, requested_at, "
                        + "verified_at, status, note FROM payroll_payments ORDER BY requested_at DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Payment(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
                            rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getString(10), rs.getString(11)));
                }
            }
        }
        return out;
    }
}

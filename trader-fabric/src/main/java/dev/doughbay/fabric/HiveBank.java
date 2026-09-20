package dev.doughbay.fabric;

import dev.doughbay.storage.Database;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Money moving between the hive's own accounts.
 *
 * <p>Two accounts trading the same market rarely have the right money in the
 * right place: one sits on twenty million while the other passes up a trade it
 * cannot afford. They share a ledger, so they can also share a purse. A client
 * short of cash writes what it needs; a client with spare cash above its own
 * reserve sees the row, pays it, and the loan is recorded. When the trade the
 * loan paid for finally sells, the borrower sends back the principal and a
 * share of that trade's profit.
 *
 * <p>Nothing here is guessed. Every step is a row written before the money
 * moves, and the {@code /pay} receipt in chat is what settles it, exactly as
 * payroll works. A loan nobody answers simply expires.
 */
public final class HiveBank {
    /** A request nobody has funded within this long is dropped. */
    private static final long REQUEST_TTL_MILLIS = 5 * 60_000L;
    private static final long POLL_MILLIS = 15_000L;

    private final Path databasePath;
    private volatile long polledAt;
    private volatile String status = "";
    private volatile long shortNotedAt;

    public HiveBank(Path databasePath) {
        this.databasePath = databasePath;
    }

    public String status() {
        return status;
    }

    private static boolean enabled() {
        return DoughBayClient.multiClient() && Tuning.get("hive.lending") >= 0.5;
    }

    private static String me() {
        try {
            String n = Minecraft.getInstance().getUser().getName();
            return n == null ? "" : n;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Asks the hive for money. Returns the loan's id, or 0 when lending is off,
     * nobody has the cash, or a request is already outstanding.
     */
    public long request(long amount, String reason) {
        if (!enabled() || amount <= 0) return 0;
        String me = me();
        if (me.isEmpty()) return 0;
        long cap = Math.round(Tuning.get("hive.max_loan"));
        if (cap > 0) amount = Math.min(amount, cap);
        try (Database db = new Database(databasePath)) {
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT COUNT(*) FROM hive_loans WHERE borrower=? AND status IN ('ASKED','SENT')")) {
                ps.setString(1, me);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) return 0;   // one at a time
                }
            }
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "INSERT INTO hive_loans (borrower, amount, reason, share_pct, status, requested_at) "
                            + "VALUES (?,?,?,?,'ASKED',?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, me);
                ps.setLong(2, amount);
                ps.setString(3, reason == null ? "" : reason);
                ps.setDouble(4, Tuning.get("hive.profit_share_pct"));
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    long id = keys.next() ? keys.getLong(1) : 0;
                    DoughBayClient.LOGGER.info("DoughBay hive: asked the others for {} ({})", amount, reason);
                    status = "asked for " + amount;
                    return id;
                }
            }
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay hive could not ask for money: {}", e.toString());
            return 0;
        }
    }

    /** Whether a loan has been funded, so the borrower may go ahead. */
    public boolean funded(long loanId) {
        if (loanId <= 0) return false;
        try (Database db = new Database(databasePath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "SELECT status FROM hive_loans WHERE loan_id=?")) {
            ps.setLong(1, loanId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "SENT".equals(rs.getString(1));
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Called on the client tick. Funds what this client can afford, drops the
     * requests nobody answered, and repays what this client owes on trades
     * that have since sold.
     */
    /**
     * Opens a consignment: a friend handed over a filled box, so they are the
     * lender and the contents are the stake. Nothing is paid now; when the box
     * sells, the same repayment that settles a cash loan pays their share of
     * what it actually made.
     *
     * @return the row id to stamp on the position, or 0 if it could not be opened
     */
    public long consign(String from, String what, long value, double sharePct) {
        String me = me();
        try (Database db = new Database(databasePath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "INSERT INTO hive_loans (borrower, lender, amount, reason, share_pct, status, requested_at, sent_at) "
                             + "VALUES (?,?,?,?,?,'SENT',?,?)",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            long now = System.currentTimeMillis();
            ps.setString(1, me);
            ps.setString(2, from);
            ps.setLong(3, value);
            ps.setString(4, "consigned " + what);
            ps.setDouble(5, sharePct);
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                long id = keys.next() ? keys.getLong(1) : 0;
                DoughBayClient.LOGGER.info("DoughBay consignment #{}: {} from {} valued {}", id, what, from, value);
                return id;
            }
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay could not open a consignment: {}", e.toString());
            return 0;
        }
    }

    public void tick(Minecraft client, long myBalance) {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        if (now - polledAt < POLL_MILLIS) return;
        polledAt = now;
        String me = me();
        if (me.isEmpty() || client == null || client.getConnection() == null) return;
        try (Database db = new Database(databasePath)) {
            expire(db, now);
            lend(db, client, me, myBalance, now);
            repay(db, client, me, now);
        } catch (Exception e) {
            DoughBayClient.LOGGER.warn("DoughBay hive bank: {}", e.toString());
        }
    }

    private void expire(Database db, long now) throws Exception {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE hive_loans SET status='EXPIRED', note='nobody had the money' "
                        + "WHERE status='ASKED' AND requested_at < ?")) {
            ps.setLong(1, now - REQUEST_TTL_MILLIS);
            ps.executeUpdate();
        }
    }

    /** Fund somebody else's request, if this client can spare it. */
    private void lend(Database db, Minecraft client, String me, long myBalance, long now) throws Exception {
        if (myBalance <= 0) return;
        long reserve = Math.round(Tuning.get("hive.reserve"));
        long spare = myBalance - reserve;
        if (spare <= 0) return;
        long loanId = 0;
        String borrower = "";
        long amount = 0;
        String reason = "";
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT loan_id, borrower, amount, reason FROM hive_loans "
                        + "WHERE status='ASKED' AND borrower<>? ORDER BY requested_at LIMIT 1")) {
            ps.setString(1, me);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return;
                loanId = rs.getLong(1);
                borrower = rs.getString(2);
                amount = rs.getLong(3);
                reason = rs.getString(4);
            }
        }
        if (amount > spare) {
            // Said once a minute, not once a poll: a request nobody can fund
            // sits there for five minutes and would otherwise fill the log.
            if (now - shortNotedAt > 60_000L) {
                shortNotedAt = now;
                DoughBayClient.LOGGER.info("DoughBay hive: {} wants {} but only {} is spare; leaving it",
                        borrower, amount, spare);
            }
            return;
        }
        // Claim it first: the row is the lock, so two lenders cannot both pay.
        int claimed;
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE hive_loans SET lender=?, status='SENT', sent_at=? WHERE loan_id=? AND status='ASKED'")) {
            ps.setString(1, me);
            ps.setLong(2, now);
            ps.setLong(3, loanId);
            claimed = ps.executeUpdate();
        }
        if (claimed != 1) return;
        DoughBayClient.executionDriver().sendWhenClear(client, "pay " + borrower + " " + amount);
        DoughBayClient.LOGGER.info("DoughBay hive: lent {} to {} for {}", amount, borrower, reason);
        status = "lent " + amount + " to " + borrower;
    }

    /**
     * Repay the loans whose trade has sold: the principal back, plus the agreed
     * share of what that trade actually made.
     */
    private void repay(Database db, Minecraft client, String me, long now) throws Exception {
        List<long[]> due = new ArrayList<>();
        List<String> lenders = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT l.loan_id, l.lender, l.amount, l.share_pct, "
                        + "COALESCE((SELECT SUM(p.realized_profit) FROM positions p "
                        + "          WHERE p.loan_id = l.loan_id AND p.status='SOLD'), 0), "
                        + "COALESCE((SELECT COUNT(*) FROM positions p "
                        + "          WHERE p.loan_id = l.loan_id AND p.status NOT IN ('SOLD','CANCELLED','EXPIRED')), 0) "
                        + "FROM hive_loans l WHERE l.borrower=? AND l.status='SENT'")) {
            ps.setString(1, me);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long stillOpen = rs.getLong(6);
                    if (stillOpen > 0) continue;          // the trade has not finished
                    double profit = rs.getDouble(5);
                    double share = rs.getDouble(4);
                    long back = rs.getLong(3) + Math.max(0, Math.round(profit * share / 100.0));
                    due.add(new long[] {rs.getLong(1), back});
                    lenders.add(rs.getString(2));
                }
            }
        }
        for (int i = 0; i < due.size(); i++) {
            long loanId = due.get(i)[0];
            long back = due.get(i)[1];
            String lender = lenders.get(i);
            if (lender == null || lender.isBlank() || back <= 0) continue;
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "UPDATE hive_loans SET status='REPAID', repaid_at=?, repaid=? WHERE loan_id=? AND status='SENT'")) {
                ps.setLong(1, now);
                ps.setLong(2, back);
                ps.setLong(3, loanId);
                if (ps.executeUpdate() != 1) continue;
            }
            DoughBayClient.executionDriver().sendWhenClear(client, "pay " + lender + " " + back);
            DoughBayClient.LOGGER.info("DoughBay hive: repaid {} to {} (principal plus its share of the profit)",
                    back, lender);
            status = "repaid " + back + " to " + lender;
        }
    }

    /** "alice $27.8m · bob $2.1m", for the status line. */
    public String balances() {
        try (Database db = new Database(databasePath);
             PreparedStatement ps = db.connection().prepareStatement(
                     "SELECT name, balance FROM api_clients WHERE seen_at > ? AND balance >= 0 ORDER BY balance DESC")) {
            ps.setLong(1, System.currentTimeMillis() - 90_000L);
            StringBuilder out = new StringBuilder();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (out.length() > 0) out.append(" · ");
                    out.append(rs.getString(1)).append(' ').append(money(rs.getLong(2)));
                }
            }
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String money(long v) {
        if (v >= 1_000_000) return String.format(java.util.Locale.ROOT, "$%.1fm", v / 1_000_000.0);
        if (v >= 1_000) return String.format(java.util.Locale.ROOT, "$%.0fk", v / 1_000.0);
        return "$" + v;
    }
}

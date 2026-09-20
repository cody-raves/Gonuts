package dev.doughbay.fabric;

import dev.doughbay.fabric.automation.AutomationSessionController;
import dev.doughbay.storage.Database;
import dev.doughbay.storage.PayrollRepository;
import dev.doughbay.storage.PayrollRepository.Payment;
import dev.doughbay.storage.PayrollRepository.Rule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pays people a share of the profit. Each rule names a recipient, a percent
 * of the profit made since the rule's last payout, and a trigger for when
 * to pay: every N hours, every N sales, or once the balance reaches X. A
 * reserve keeps a floor in the bank and a daily cap bounds any one name.
 *
 * <p>Like every other automatic action, nothing runs unless the player has
 * pressed Start or Resume. Adding a rule is the deliberate act: it pays from
 * then on, and the tab shows every payment as it happens. The payment row is written before {@code /pay} goes out, and the chat receipt
 * settles it; one payment is in flight at a time.
 */
public final class Payroll {
    public enum Trigger { HOURS, SALES, BALANCE }

    private static final long CHECK_MILLIS = 5_000;
    private static final long RECEIPT_TIMEOUT_MILLIS = 12_000;
    private static final long DAY_MILLIS = 24 * 3_600_000L;

    /** A first payment waiting for the confirming click. */
    public record PendingConfirmation(long ruleId, String name, long amount, long profit) {
    }

    private record Awaiting(long paymentId, long ruleId, String name, long amount, long deadline) {
    }

    private final Path databasePath;
    private final AutomatedExecutionDriver driver;
    private final AutomationSessionController session;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "doughbay-payroll");
        t.setDaemon(true);
        return t;
    });
    private volatile List<Rule> rules = List.of();
    private volatile List<Payment> payments = List.of();
    private volatile String status = "";
    private volatile PendingConfirmation pending;
    private volatile Awaiting awaiting;
    private volatile boolean busy;
    private long nextCheckAt;
    private boolean onlineLookupWarned;

    public Payroll(Path databasePath, AutomatedExecutionDriver driver, AutomationSessionController session) {
        this.databasePath = databasePath;
        this.driver = driver;
        this.session = session;
        executor.submit(this::reload);
    }

    public List<Rule> rules() {
        return rules;
    }

    public List<Payment> payments() {
        return payments;
    }

    public String status() {
        return status;
    }

    public PendingConfirmation pendingConfirmation() {
        return pending;
    }

    public boolean paymentInFlight() {
        return awaiting != null;
    }

    public void addOrUpdate(Rule rule) {
        executor.submit(() -> withRepo("save", repo -> repo.save(rule)));
    }

    public void delete(long ruleId) {
        if (pending != null && pending.ruleId() == ruleId) pending = null;
        executor.submit(() -> withRepo("delete", repo -> repo.delete(ruleId)));
    }

    public void setPaused(long ruleId, boolean paused) {
        executor.submit(() -> withRepo("pause", repo -> repo.setPaused(ruleId, paused)));
    }

    /** The confirming click: the rule may pay from now on, starting on the next check. */
    public void confirm(long ruleId) {
        if (pending != null && pending.ruleId() == ruleId) pending = null;
        executor.submit(() -> withRepo("confirm", repo -> repo.setConfirmed(ruleId, true)));
    }

    /** The server's answer to {@code /pay}: a receipt naming the recipient settles the payment in flight. */
    private static final java.util.regex.Pattern PAID_LINE = java.util.regex.Pattern.compile(
            "(?i)you paid ([A-Za-z0-9_.]{3,20}) [$] ?([0-9][0-9,.]*)([kmb]?)");

    public void observeChat(String text) {
        if (text == null) return;
        Awaiting a = awaiting;
        if (a == null) {
            // A payment the player made by hand, to a name with a rule: it
            // belongs in that name's total. The profit window is not touched.
            java.util.regex.Matcher m = PAID_LINE.matcher(text.strip());
            if (!m.find()) return;
            String who = m.group(1);
            long amount = parseMoney(m.group(2), m.group(3));
            if (amount <= 0) return;
            for (Rule r : rules) {
                if (!r.name().equalsIgnoreCase(who)) continue;
                long now = System.currentTimeMillis();
                String line = text.strip();
                executor.submit(() -> withRepo("manual", repo -> repo.recordManual(r, amount, now, line)));
                status = "Recorded your own payment of " + amount + " to " + r.name();
                DoughBayClient.LOGGER.info("DoughBay payroll: manual payment recorded -> {}", line);
                return;
            }
            return;
        }
        String t = text.toLowerCase(Locale.ROOT);
        if (!t.contains(a.name().toLowerCase(Locale.ROOT))) return;
        boolean failed = t.contains("not enough") || t.contains("insufficient") || t.contains("not online")
                || t.contains("isn't online") || t.contains("is not online") || t.contains("not found")
                || t.contains("could not") || t.contains("cannot") || t.contains("can't") || t.contains("unknown player");
        boolean paid = t.contains("$") || t.contains("paid") || t.contains("sent");
        if (!failed && !paid) return;
        awaiting = null;
        long now = System.currentTimeMillis();
        String line = text.strip();
        executor.submit(() -> withRepo("settle", repo -> {
            repo.settle(a.paymentId(), failed ? "FAILED" : "PAID", now, line);
            if (failed) {
                repo.setPaused(a.ruleId(), true);
            } else {
                repo.markPaid(a.ruleId(), now, a.amount());
            }
        }));
        status = failed ? "Payment to " + a.name() + " failed and the rule is paused: " + line
                : "Paid " + a.name() + " " + a.amount() + ": " + line;
        DoughBayClient.LOGGER.info("DoughBay payroll: {} -> {}", failed ? "FAILED" : "PAID", line);
    }

    /** Called every client tick; does its work every few seconds on the payroll thread. */
    public void tick(Minecraft client) {
        long now = System.currentTimeMillis();
        Awaiting a = awaiting;
        if (a != null && now > a.deadline()) {
            // No receipt within the window. Two days of logs say what that
            // means: the server dropped the command, usually because another
            // went out a quarter second earlier, and the money never moved.
            // The amount stays owed and rides into the next payment; the
            // window itself moves on so the profit is not counted twice.
            awaiting = null;
            executor.submit(() -> withRepo("timeout", repo -> {
                repo.settle(a.paymentId(), "UNPAID", now,
                        "no receipt within 12 s; the command was dropped; carried into the next payment");
                repo.touchPaid(a.ruleId(), now);
            }));
            DoughBayClient.LOGGER.warn("DoughBay payroll: no receipt for {} to {}; it stays owed and is added to the next payment",
                    a.amount(), a.name());
            status = a.name() + ": " + a.amount() + " was not paid; it is added to the next payment";
        }
        if (now < nextCheckAt || busy) return;
        nextCheckAt = now + CHECK_MILLIS;
        if (client == null || client.player == null || client.getConnection() == null) return;
        List<Rule> current = rules;
        if (current.isEmpty()) return;
        // Payments go out only while the session runs and the bot is between
        // operations. A first payment's confirm button, though, is worked out
        // even while the session is stopped: the bot closes this screen every
        // time it opens the auction house, so the click has to be possible
        // while nothing is running.
        // The server spaces commands; a /pay on the heels of an /ah cost the
        // bot an eight-second timeout, so payroll keeps clear of the bot's last command.
        // A payment is another command, and an unanswered one is carried into
        // the next payment and tried again. That retry is part of what fills
        // the pipe when the server has gone quiet, so payroll waits too.
        boolean live = session.armed() && awaiting == null
                && !ServerStrain.holding()
                && driver.operationIntent() == AutomatedExecutionDriver.OperationIntent.NONE
                && now - driver.lastCommandSentAt() >= 1_500;
        if (!live) return;
        long balance = session.knownBalance();
        Set<String> online = onlineNames(client);
        busy = true;
        executor.submit(() -> {
            try {
                evaluate(client, current, balance, online, now, live);
            } finally {
                busy = false;
            }
        });
    }

    private void evaluate(Minecraft client, List<Rule> current, long balance, Set<String> online, long now, boolean live) {
        try (Database db = new Database(databasePath)) {
            PayrollRepository repo = new PayrollRepository(db);
            for (Rule r : current) {
                if (r.paused()) continue;
                if (r.onlyOnline() && !online.contains(r.name().toLowerCase(Locale.ROOT))) continue;
                Trigger trigger;
                try {
                    trigger = Trigger.valueOf(r.triggerKind());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                long from = r.lastPaidAt() > 0 ? r.lastPaidAt() : r.createdAt();
                boolean due = switch (trigger) {
                    case HOURS -> now - from >= r.triggerValue() * 3_600_000L;
                    case SALES -> repo.salesSince(from, now) >= r.triggerValue();
                    case BALANCE -> balance >= 0 && balance >= r.triggerValue();
                };
                if (!due) continue;
                long profit = repo.profitSince(from, now);
                long amount = (long) Math.floor(profit * r.percent() / 100.0);
                long owed = repo.unpaidSum(r.ruleId());
                amount += owed;
                if (r.dailyCap() > 0) amount = Math.min(amount, r.dailyCap() - repo.paidSince(r.ruleId(), now - DAY_MILLIS));
                if (balance >= 0 && r.reserve() > 0) amount = Math.min(amount, balance - r.reserve());
                if (amount < 1) {
                    if (trigger != Trigger.BALANCE && live) repo.touchPaid(r.ruleId(), now);
                    status = r.name() + ": nothing to pay this window (profit " + profit + ", reserve and cap applied)";
                    continue;
                }

                Payment payment = repo.recordPayment(r, amount, from, now, profit, now);
                if (owed > 0) {
                    repo.markCarried(r.ruleId(), payment.paymentId(), now);
                    DoughBayClient.LOGGER.info("DoughBay payroll: {} owed from dropped payments is included in this one to {}",
                            owed, r.name());
                }
                awaiting = new Awaiting(payment.paymentId(), r.ruleId(), r.name(), amount, now + RECEIPT_TIMEOUT_MILLIS);
                String command = "pay " + r.name() + " " + amount;
                DoughBayClient.LOGGER.info("DoughBay payroll: /{} ({}% of {} profit since {})",
                        command, r.percent(), profit, from);
                status = "Paying " + r.name() + " " + amount + " (" + r.percent() + "% of " + profit + " profit)";
                client.execute(() -> {
                    try {
                        ClientPacketListener connection = client.getConnection();
                        if (connection == null) throw new IllegalStateException("not connected");
                        driver.sendWhenClear(client, command);
                    } catch (RuntimeException e) {
                        awaiting = null;
                        long at = System.currentTimeMillis();
                        executor.submit(() -> withRepo("send-failed", repo2 ->
                                repo2.settle(payment.paymentId(), "FAILED", at, "could not send: " + e.getMessage())));
                        status = "Could not send /" + command + ": " + e.getMessage();
                    }
                });
                break;   // one payment per check; the receipt must settle before the next
            }
            rules = repo.rules();
            payments = repo.recentPayments(12);
        } catch (Exception e) {
            status = "Payroll error: " + e.getMessage();
            DoughBayClient.LOGGER.warn("DoughBay payroll check failed", e);
        }
    }

    /** "13.7K" with a suffix, or a plain number with separators, as whole coins. */
    private static long parseMoney(String digits, String suffix) {
        try {
            double v = Double.parseDouble(digits.replace(",", ""));
            switch (suffix == null ? "" : suffix.toLowerCase(Locale.ROOT)) {
                case "k" -> v *= 1_000;
                case "m" -> v *= 1_000_000;
                case "b" -> v *= 1_000_000_000;
                default -> { }
            }
            return Math.round(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Set<String> onlineNames(Minecraft client) {
        Set<String> names = new HashSet<>();
        try {
            ClientPacketListener connection = client.getConnection();
            if (connection == null) return names;
            for (PlayerInfo info : connection.getListedOnlinePlayers()) {
                names.add(info.getProfile().name().toLowerCase(Locale.ROOT));
            }
        } catch (RuntimeException | LinkageError e) {
            if (!onlineLookupWarned) {
                onlineLookupWarned = true;
                DoughBayClient.LOGGER.warn("DoughBay payroll cannot read the player list; online-only rules will not pay: {}", e.toString());
            }
        }
        return names;
    }

    private interface RepoAction {
        void run(PayrollRepository repo) throws Exception;
    }

    private void withRepo(String what, RepoAction action) {
        try (Database db = new Database(databasePath)) {
            PayrollRepository repo = new PayrollRepository(db);
            action.run(repo);
            rules = repo.rules();
            payments = repo.recentPayments(12);
        } catch (Exception e) {
            status = "Payroll " + what + " failed: " + e.getMessage();
            DoughBayClient.LOGGER.warn("DoughBay payroll {} failed", what, e);
        }
    }

    private void reload() {
        withRepo("load", repo -> { });
    }
}

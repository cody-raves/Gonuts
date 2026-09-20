package dev.doughbay.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite database with versioned migrations. Raw API JSON is stored next to
 * every parsed record so parsing can be replayed if the API shape changes.
 *
 * <p>Connections are opened through an explicitly constructed driver rather
 * than {@link DriverManager}. Under Minecraft's Fabric class loader,
 * DriverManager's service discovery runs once per JVM against whichever
 * thread's context class loader touches it first; if that thread is a
 * ForkJoinPool worker (system class loader), the scan finds no driver and
 * caches that failure permanently, so every later connection fails too.
 * Constructing the driver directly sidesteps discovery entirely.
 */
public final class Database implements AutoCloseable {

    private final Connection connection;

    private static Connection open(String url) throws SQLException {
        Connection c = new org.sqlite.JDBC().connect(url, new java.util.Properties());
        if (c == null) {
            // The driver declined the URL, which means it is malformed.
            throw new SQLException("SQLite driver rejected the connection URL: " + url);
        }
        return c;
    }

    public Database(Path file) throws SQLException {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
        } catch (Exception e) {
            throw new SQLException("Cannot create database directory: " + e.getMessage(), e);
        }
        this.connection = open("jdbc:sqlite:" + file);
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            // Two clients share one large ledger, and a restart has both
            // migrating and batch-writing at once; five seconds was not
            // enough and the loser's watcher died on SQLITE_BUSY.
            st.execute("PRAGMA busy_timeout=30000");
        }
        migrate();
    }

    /** In-memory database for tests. */
    public static Database inMemory() throws SQLException {
        try {
            Connection c = open("jdbc:sqlite::memory:");
            return new Database(c);
        } catch (SQLException e) {
            throw e;
        }
    }

    private Database(Connection connection) throws SQLException {
        this.connection = connection;
        migrate();
    }

    public Connection connection() {
        return connection;
    }

    private void migrate() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        }
        int version = currentVersion();
        if (version < 1) {
            applyV1();
            setVersion(1);
            version = 1;
        }
        if (version < 2) {
            applyV2();
            setVersion(2);
            version = 2;
        }
        if (version < 3) {
            applyV3();
            setVersion(3);
            version = 3;
        }
        if (version < 4) {
            applyV4();
            setVersion(4);
            version = 4;
        }
        if (version < 5) {
            applyV5();
            setVersion(5);
            version = 5;
        }
        if (version < 6) {
            applyV6();
            setVersion(6);
            version = 6;
        }
        if (version < 7) {
            applyV7();
            setVersion(7);
            version = 7;
        }
        if (version < 8) {
            applyV8();
            setVersion(8);
            version = 8;
        }
        if (version < 9) {
            applyV9();
            setVersion(9);
            version = 9;
        }
        if (version < 10) {
            applyV10();
            setVersion(10);
            version = 10;
        }
        if (version < 11) {
            applyV11();
            setVersion(11);
            version = 11;
        }
        if (version < 12) {
            applyV12();
            setVersion(12);
            version = 12;
        }
        if (version < 13) {
            applyV13();
            setVersion(13);
            version = 13;
        }
        if (version < 14) {
            applyV14();
            setVersion(14);
            version = 14;
        }
        if (version < 15) {
            applyV15();
            setVersion(15);
            version = 15;
        }
    }

    /**
     * One session checkpoint per client instead of one for the whole ledger.
     *
     * <p>The table was written when a ledger meant one client, so it held a
     * single row keyed on {@code singleton_id = 1}. With two clients running
     * they overwrite each other continuously: the second one reads the first's
     * committed spend against its own smaller balance and concludes it has
     * already spent its allowance, which is exactly what stopped the main
     * trading. Positions have carried an owner since v12; the session did not.
     *
     * <p>The existing row is kept as an unowned fallback so lifetime runtime is
     * not lost, but its session-scoped figures are zeroed: committed spend
     * rebuilds correctly from each client's own open positions, and carrying
     * the old number into a client that does not own those positions is the
     * bug being fixed.
     */
    private void applyV14() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE automation_session_state RENAME TO automation_session_state_v13");
            st.execute(sessionStateDdl());
            st.execute("""
                    INSERT INTO automation_session_state
                    SELECT '', session_open, run_mode, controller_state, 0, 0,
                           session_started_at, updated_at, detail, bound_listing_key,
                           uncertain_active, uncertain_phase, uncertain_listing_key, uncertain_item_key,
                           uncertain_item_id, uncertain_item_count, uncertain_buy_price,
                           uncertain_target_price, uncertain_seller, uncertain_observed_at,
                           0, lifetime_active_millis
                    FROM automation_session_state_v13""");
            st.execute("DROP TABLE automation_session_state_v13");
        }
    }

    /** The session checkpoint, keyed by the account that owns it. */
    private static String sessionStateDdl() {
        return """
                CREATE TABLE automation_session_state (
                    client                TEXT PRIMARY KEY,
                    session_open          INTEGER NOT NULL CHECK (session_open IN (0, 1)),
                    run_mode              TEXT NOT NULL,
                    controller_state      TEXT NOT NULL,
                    trades_started        INTEGER NOT NULL CHECK (trades_started >= 0),
                    committed_spend       INTEGER NOT NULL CHECK (committed_spend >= 0),
                    session_started_at    INTEGER NOT NULL CHECK (session_started_at >= 0),
                    updated_at            INTEGER NOT NULL CHECK (updated_at >= 0),
                    detail                TEXT NOT NULL DEFAULT '',
                    bound_listing_key     TEXT NOT NULL DEFAULT '',
                    uncertain_active      INTEGER NOT NULL CHECK (uncertain_active IN (0, 1)),
                    uncertain_phase       TEXT NOT NULL DEFAULT '',
                    uncertain_listing_key TEXT NOT NULL DEFAULT '',
                    uncertain_item_key    TEXT NOT NULL DEFAULT '',
                    uncertain_item_id     TEXT NOT NULL DEFAULT '',
                    uncertain_item_count  INTEGER NOT NULL DEFAULT 0 CHECK (uncertain_item_count >= 0),
                    uncertain_buy_price   INTEGER NOT NULL DEFAULT 0 CHECK (uncertain_buy_price >= 0),
                    uncertain_target_price INTEGER NOT NULL DEFAULT 0 CHECK (uncertain_target_price >= 0),
                    uncertain_seller      TEXT NOT NULL DEFAULT '',
                    uncertain_observed_at INTEGER NOT NULL DEFAULT 0 CHECK (uncertain_observed_at >= 0),
                    session_active_millis INTEGER NOT NULL DEFAULT 0 CHECK (session_active_millis >= 0),
                    lifetime_active_millis INTEGER NOT NULL DEFAULT 0 CHECK (lifetime_active_millis >= 0)
                )""";
    }

    private int currentVersion() throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void setVersion(int version) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("INSERT INTO schema_version(version) VALUES (" + version + ")");
        }
    }

    private void applyV1() throws SQLException {
        String[] ddl = {
                """
                CREATE TABLE IF NOT EXISTS transactions (
                    transaction_hash TEXT PRIMARY KEY,
                    sold_at          INTEGER NOT NULL,
                    seller_uuid      TEXT NOT NULL,
                    seller_name      TEXT NOT NULL DEFAULT '',
                    item_key         TEXT NOT NULL,
                    item_id          TEXT NOT NULL,
                    item_count       INTEGER NOT NULL,
                    total_price      INTEGER NOT NULL,
                    unit_price       REAL NOT NULL,
                    is_outlier       INTEGER NOT NULL DEFAULT 0,
                    raw_json         TEXT NOT NULL
                )""",
                "CREATE INDEX IF NOT EXISTS idx_tx_item_time ON transactions(item_key, sold_at)",
                "CREATE INDEX IF NOT EXISTS idx_tx_time ON transactions(sold_at)",
                """
                CREATE TABLE IF NOT EXISTS listing_snapshots (
                    snapshot_id  INTEGER PRIMARY KEY AUTOINCREMENT,
                    observed_at  INTEGER NOT NULL,
                    listing_key  TEXT NOT NULL,
                    seller_uuid  TEXT NOT NULL DEFAULT '',
                    seller_name  TEXT NOT NULL DEFAULT '',
                    item_key     TEXT NOT NULL,
                    item_id      TEXT NOT NULL,
                    item_count   INTEGER NOT NULL,
                    total_price  INTEGER NOT NULL,
                    unit_price   REAL NOT NULL,
                    time_left    INTEGER,
                    raw_json     TEXT NOT NULL
                )""",
                "CREATE INDEX IF NOT EXISTS idx_ls_item_time ON listing_snapshots(item_key, observed_at)",
                "CREATE INDEX IF NOT EXISTS idx_ls_listing ON listing_snapshots(listing_key, observed_at)",
                """
                CREATE TABLE IF NOT EXISTS items (
                    item_key           TEXT PRIMARY KEY,
                    item_id            TEXT NOT NULL,
                    display_name       TEXT NOT NULL DEFAULT '',
                    metadata_json      TEXT NOT NULL DEFAULT '',
                    commodity_eligible INTEGER NOT NULL DEFAULT 0,
                    first_seen         INTEGER NOT NULL,
                    last_seen          INTEGER NOT NULL
                )""",
                """
                CREATE TABLE IF NOT EXISTS market_stats (
                    item_key          TEXT NOT NULL,
                    stack_bucket      TEXT NOT NULL,
                    window_millis     INTEGER NOT NULL,
                    sample_count      INTEGER NOT NULL,
                    outlier_count     INTEGER NOT NULL,
                    unique_sellers    INTEGER NOT NULL,
                    newest_sale_at    INTEGER NOT NULL,
                    lower_bound       REAL,
                    quick_sale_price  REAL,
                    weighted_median   REAL,
                    patient_sale_price REAL,
                    upper_bound       REAL,
                    sales_per_hour    REAL NOT NULL,
                    robust_volatility REAL NOT NULL,
                    trend             REAL NOT NULL,
                    confidence        REAL NOT NULL,
                    calculated_at     INTEGER NOT NULL,
                    PRIMARY KEY (item_key, stack_bucket)
                )""",
                """
                CREATE TABLE IF NOT EXISTS opportunities (
                    opportunity_id        INTEGER PRIMARY KEY AUTOINCREMENT,
                    detected_at           INTEGER NOT NULL,
                    listing_key           TEXT NOT NULL,
                    item_key              TEXT NOT NULL,
                    item_count            INTEGER NOT NULL,
                    buy_price             INTEGER NOT NULL,
                    recommended_sell_price INTEGER NOT NULL,
                    expected_profit       REAL NOT NULL,
                    expected_roi          REAL NOT NULL,
                    estimated_hold_hours  REAL NOT NULL,
                    sale_probability      REAL NOT NULL,
                    confidence            REAL NOT NULL,
                    score                 REAL NOT NULL,
                    reasons               TEXT NOT NULL DEFAULT '',
                    status                TEXT NOT NULL DEFAULT 'DETECTED'
                )""",
                "CREATE INDEX IF NOT EXISTS idx_opp_time ON opportunities(detected_at)",
                """
                CREATE TABLE IF NOT EXISTS positions (
                    position_id     INTEGER PRIMARY KEY AUTOINCREMENT,
                    mode            TEXT NOT NULL,
                    item_key        TEXT NOT NULL,
                    stack_bucket    TEXT NOT NULL,
                    quantity        INTEGER NOT NULL,
                    purchase_price  INTEGER NOT NULL,
                    target_price    INTEGER NOT NULL,
                    purchased_at    INTEGER,
                    listed_at       INTEGER,
                    closed_at       INTEGER,
                    sale_price      INTEGER,
                    realized_profit REAL,
                    status          TEXT NOT NULL
                )""",
                "CREATE INDEX IF NOT EXISTS idx_pos_status ON positions(status)",
                """
                CREATE TABLE IF NOT EXISTS raw_pages (
                    page_id    INTEGER PRIMARY KEY AUTOINCREMENT,
                    fetched_at INTEGER NOT NULL,
                    endpoint   TEXT NOT NULL,
                    raw_json   TEXT NOT NULL
                )""",
                """
                CREATE TABLE IF NOT EXISTS balance_history (
                    recorded_at     INTEGER NOT NULL,
                    mode            TEXT NOT NULL,
                    balance         INTEGER NOT NULL,
                    inventory_value INTEGER NOT NULL
                )"""
        };
        try (Statement st = connection.createStatement()) {
            for (String sql : ddl) {
                st.execute(sql);
            }
        }
    }

    /**
     * Durable, fail-closed state for the one real automation session.
     *
     * <p>The position itself remains in {@code positions}; this singleton row
     * stores the session caps and enough exact intent to retain an uncertain
     * in-flight BUY even before an inventory delta can create a Position.
     */
    private void applyV2() throws SQLException {
        String ddl = """
                CREATE TABLE IF NOT EXISTS automation_session_state (
                    singleton_id          INTEGER PRIMARY KEY CHECK (singleton_id = 1),
                    session_open          INTEGER NOT NULL CHECK (session_open IN (0, 1)),
                    run_mode              TEXT NOT NULL,
                    controller_state      TEXT NOT NULL,
                    trades_started        INTEGER NOT NULL CHECK (trades_started >= 0),
                    committed_spend       INTEGER NOT NULL CHECK (committed_spend >= 0),
                    session_started_at    INTEGER NOT NULL CHECK (session_started_at >= 0),
                    updated_at            INTEGER NOT NULL CHECK (updated_at >= 0),
                    detail                TEXT NOT NULL DEFAULT '',
                    bound_listing_key     TEXT NOT NULL DEFAULT '',
                    uncertain_active      INTEGER NOT NULL CHECK (uncertain_active IN (0, 1)),
                    uncertain_phase       TEXT NOT NULL DEFAULT '',
                    uncertain_listing_key TEXT NOT NULL DEFAULT '',
                    uncertain_item_key    TEXT NOT NULL DEFAULT '',
                    uncertain_item_id     TEXT NOT NULL DEFAULT '',
                    uncertain_item_count  INTEGER NOT NULL DEFAULT 0 CHECK (uncertain_item_count >= 0),
                    uncertain_buy_price   INTEGER NOT NULL DEFAULT 0 CHECK (uncertain_buy_price >= 0),
                    uncertain_target_price INTEGER NOT NULL DEFAULT 0 CHECK (uncertain_target_price >= 0),
                    uncertain_seller      TEXT NOT NULL DEFAULT '',
                    uncertain_observed_at INTEGER NOT NULL DEFAULT 0 CHECK (uncertain_observed_at >= 0)
                )""";
        try (Statement st = connection.createStatement()) {
            st.execute(ddl);
        }
    }

    /** Immutable audit trail for the explicit two-step recovery resolution. */
    private void applyV3() throws SQLException {
        String ddl = """
                CREATE TABLE IF NOT EXISTS automation_resolution_audit (
                    audit_id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    recorded_at          INTEGER NOT NULL,
                    position_id          INTEGER NOT NULL DEFAULT 0,
                    phase                TEXT NOT NULL,
                    item_key             TEXT NOT NULL,
                    item_id              TEXT NOT NULL,
                    item_count           INTEGER NOT NULL,
                    buy_price            INTEGER NOT NULL,
                    target_price         INTEGER NOT NULL,
                    first_confirmed_at   INTEGER NOT NULL,
                    second_confirmed_at  INTEGER NOT NULL,
                    evidence_observed_at INTEGER NOT NULL,
                    evidence_summary     TEXT NOT NULL,
                    resolution_detail    TEXT NOT NULL
                )""";
        try (Statement st = connection.createStatement()) {
            st.execute(ddl);
        }
    }

    /**
     * Monotonic active-time totals. The controller supplies elapsed time from
     * {@code System.nanoTime()}, so wall-clock jumps and offline restart gaps
     * are never interpreted as automation runtime.
     */
    private void applyV4() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE automation_session_state "
                    + "ADD COLUMN session_active_millis INTEGER NOT NULL DEFAULT 0 "
                    + "CHECK (session_active_millis >= 0)");
            st.execute("ALTER TABLE automation_session_state "
                    + "ADD COLUMN lifetime_active_millis INTEGER NOT NULL DEFAULT 0 "
                    + "CHECK (lifetime_active_millis >= 0)");
        }
    }

    /** Who bought the mod's own listings, from the sale chat line. */
    private void applyV5() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS sale_buyers (
                        row_id      INTEGER PRIMARY KEY AUTOINCREMENT,
                        position_id INTEGER NOT NULL,
                        buyer_name  TEXT NOT NULL,
                        item_key    TEXT NOT NULL,
                        quantity    INTEGER NOT NULL,
                        sale_price  INTEGER NOT NULL,
                        sold_at     INTEGER NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_sale_buyers_name ON sale_buyers(buyer_name, sold_at)");
        }
    }

    /**
     * Auction rows whose value lives in their components (enchantments,
     * effects, trims, container contents), which the API feed hides, and
     * the part values fitted from them.
     */
    private void applyV6() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS component_listings (
                        row_id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        observed_at    INTEGER NOT NULL,
                        page           TEXT NOT NULL,
                        item_id        TEXT NOT NULL,
                        count          INTEGER NOT NULL,
                        total_price    INTEGER NOT NULL,
                        seller_name    TEXT NOT NULL,
                        descriptor_key TEXT NOT NULL,
                        descriptor_json TEXT NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_component_key ON component_listings(descriptor_key, observed_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_component_item ON component_listings(item_id, observed_at)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS part_values (
                        part_key   TEXT PRIMARY KEY,
                        value      INTEGER NOT NULL,
                        samples    INTEGER NOT NULL,
                        confidence REAL NOT NULL,
                        updated_at INTEGER NOT NULL
                    )""");
        }
    }

    /** Payroll rules and the payments made under them. */
    private void applyV7() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS payroll_rules (
                        rule_id       INTEGER PRIMARY KEY,
                        name          TEXT NOT NULL,
                        percent       REAL NOT NULL,
                        trigger_kind  TEXT NOT NULL,
                        trigger_value REAL NOT NULL,
                        only_online   INTEGER NOT NULL,
                        reserve       INTEGER NOT NULL,
                        daily_cap     INTEGER NOT NULL,
                        paused        INTEGER NOT NULL,
                        confirmed     INTEGER NOT NULL,
                        created_at    INTEGER NOT NULL,
                        last_paid_at  INTEGER NOT NULL,
                        total_paid    INTEGER NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS payroll_payments (
                        payment_id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        rule_id            INTEGER NOT NULL,
                        name               TEXT NOT NULL,
                        amount             INTEGER NOT NULL,
                        profit_window_from INTEGER NOT NULL,
                        profit_window_to   INTEGER NOT NULL,
                        profit             INTEGER NOT NULL,
                        requested_at       INTEGER NOT NULL,
                        verified_at        INTEGER NOT NULL,
                        status             TEXT NOT NULL,
                        note               TEXT NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_payroll_payments_rule ON payroll_payments(rule_id, requested_at)");
        }
    }

    /** The order house as read by clients: every open buy order and what it wanted. */
    private void applyV8() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS orders (
                        row_id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        observed_at     INTEGER NOT NULL,
                        item_id         TEXT NOT NULL,
                        item_key        TEXT NOT NULL,
                        descriptor_json TEXT NOT NULL,
                        parts           TEXT NOT NULL,
                        unit_price      INTEGER NOT NULL,
                        delivered       INTEGER NOT NULL,
                        total           INTEGER NOT NULL,
                        page            INTEGER NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_orders_item ON orders(item_id, observed_at)");
        }
    }

    /** A sample a minute of how many players the server is carrying. */
    private void applyV9() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS population (
                        at      INTEGER PRIMARY KEY,
                        players INTEGER NOT NULL
                    )""");
        }
    }

    /**
     * The whole network's player count beside this shard's. One auction house
     * serves every shard, so the network number is the one the market feels.
     */
    private void applyV10() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE population ADD COLUMN network INTEGER NOT NULL DEFAULT 0");
        } catch (SQLException e) {
            // already there on a ledger that has seen this build
            if (!String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("duplicate column")) throw e;
        }
    }

    /**
     * The clients sharing this ledger, and so sharing one API key. Each writes
     * its own row every few seconds; a row that stops being touched ages out,
     * so a client that crashed stops taking a share of the request budget.
     */
    private void applyV11() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS api_clients (
                        client_id TEXT PRIMARY KEY,
                        name      TEXT NOT NULL DEFAULT '',
                        role      TEXT NOT NULL DEFAULT '',
                        seen_at   INTEGER NOT NULL
                    )
                    """);
        }
    }

    /**
     * Which client owns a position. With several accounts trading against one
     * ledger, each may only manage what it bought: a listing lives in one
     * account's auction, and the other client cannot pull it back at all.
     * Empty means a position from before this column, claimed on first sight.
     */
    private void applyV12() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE positions ADD COLUMN client TEXT NOT NULL DEFAULT ''");
        } catch (SQLException e) {
            if (!String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("duplicate column")) throw e;
        }
    }

    /**
     * The hive's tip board, its control board, and the evasion log. These
     * were first written into the v13 step after v13 had already run on the
     * live ledger, so that ledger never got them - a migration that has been
     * applied is never read again, however much is added to it later. Each
     * new table gets its own step from here on.
     */
    private void applyV15() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS hive_tips (
                        tip_id     INTEGER PRIMARY KEY AUTOINCREMENT,
                        item_key   TEXT    NOT NULL,
                        item_count INTEGER NOT NULL,
                        buy_price  INTEGER NOT NULL,
                        sell_price INTEGER NOT NULL DEFAULT 0,
                        profit     INTEGER NOT NULL DEFAULT 0,
                        posted_by  TEXT    NOT NULL,
                        posted_at  INTEGER NOT NULL,
                        status     TEXT    NOT NULL DEFAULT 'OPEN',
                        claimed_by TEXT    NOT NULL DEFAULT ''
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_hive_tips_open ON hive_tips(status, posted_at)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS hive_control (
                        id        INTEGER PRIMARY KEY AUTOINCREMENT,
                        target    TEXT    NOT NULL,
                        command   TEXT    NOT NULL,
                        issued_by TEXT    NOT NULL DEFAULT '',
                        issued_at INTEGER NOT NULL,
                        status    TEXT    NOT NULL DEFAULT 'OPEN',
                        result    TEXT    NOT NULL DEFAULT '',
                        done_at   INTEGER NOT NULL DEFAULT 0
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_hive_control_open ON hive_control(status, target)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS evasion_events (
                        id       INTEGER PRIMARY KEY AUTOINCREMENT,
                        client   TEXT    NOT NULL DEFAULT '',
                        at       INTEGER NOT NULL,
                        who      TEXT    NOT NULL DEFAULT '',
                        distance REAL    NOT NULL DEFAULT 0,
                        action   TEXT    NOT NULL
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_evasion_events_at ON evasion_events(at)");
        }
    }

    /**
     * The hive's bank. Each client's balance rides along with its heartbeat,
     * so a client short of cash can see which of its siblings is flush; a loan
     * is a row here, from the asking to the repayment, and every step is
     * written before the money moves.
     */
    private void applyV13() throws SQLException {
        try (Statement st = connection.createStatement()) {
            try {
                st.execute("ALTER TABLE api_clients ADD COLUMN balance INTEGER NOT NULL DEFAULT -1");
            } catch (SQLException e) {
                if (!String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("duplicate column")) throw e;
            }
            st.execute("""
                    CREATE TABLE IF NOT EXISTS hive_loans (
                        loan_id      INTEGER PRIMARY KEY AUTOINCREMENT,
                        borrower     TEXT    NOT NULL,
                        lender       TEXT    NOT NULL DEFAULT '',
                        amount       INTEGER NOT NULL,
                        reason       TEXT    NOT NULL DEFAULT '',
                        share_pct    REAL    NOT NULL DEFAULT 50,
                        status       TEXT    NOT NULL,
                        requested_at INTEGER NOT NULL,
                        sent_at      INTEGER NOT NULL DEFAULT 0,
                        repaid_at    INTEGER NOT NULL DEFAULT 0,
                        repaid       INTEGER NOT NULL DEFAULT 0,
                        note         TEXT    NOT NULL DEFAULT ''
                    )
                    """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_hive_loans_status ON hive_loans(status)");
        }
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE positions ADD COLUMN loan_id INTEGER NOT NULL DEFAULT 0");
        } catch (SQLException e) {
            if (!String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("duplicate column")) throw e;
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}

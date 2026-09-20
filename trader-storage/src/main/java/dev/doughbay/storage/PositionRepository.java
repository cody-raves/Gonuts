package dev.doughbay.storage;

import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;
import dev.doughbay.core.model.StackBucket;
import dev.doughbay.core.performance.PerformanceMode;
import dev.doughbay.core.performance.PerformanceStats;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Paper and real positions plus the balance-over-time history. */
public final class PositionRepository {

    private final Database db;

    private static final String CLOSED_STATUSES =
            "'LIKELY_SOLD','EXPIRED','CANCELLED','MISSED_BEFORE_PURCHASE','SOLD'";

    /**
     * The account this repository works as. Empty keeps the old behaviour, in
     * which every position belongs to whoever is looking; set it and the
     * repository stamps an owner and hands back only that client's positions.
     */
    private String client = "";

    public void setClient(String name) {
        this.client = name == null ? "" : name;
    }

    /** The loan paying for what is being bought right now, or 0. */
    private long loanId;

    public void setLoanId(long id) {
        this.loanId = Math.max(0, id);
    }

    public PositionRepository(Database db) {
        this.db = db;
    }

    /** @return the position with its generated id. */
    public Position insert(Position p) throws SQLException {
        String sql = """
                INSERT INTO positions
                (mode, item_key, stack_bucket, quantity, purchase_price, target_price,
                 purchased_at, listed_at, closed_at, sale_price, realized_profit, status, client, loan_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""";
        try (PreparedStatement ps = db.connection().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, p);
            ps.setString(13, client);
            ps.setLong(14, loanId);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                long id = keys.next() ? keys.getLong(1) : 0;
                return new Position(id, p.mode(), p.itemKey(), p.bucket(), p.quantity(),
                        p.purchasePrice(), p.targetPrice(), p.purchasedAt(), p.listedAt(),
                        p.closedAt(), p.salePrice(), p.realizedProfit(), p.status());
            }
        }
    }

    public void update(Position p) throws SQLException {
        String sql = """
                UPDATE positions SET mode=?, item_key=?, stack_bucket=?, quantity=?,
                    purchase_price=?, target_price=?, purchased_at=?, listed_at=?, closed_at=?,
                    sale_price=?, realized_profit=?, status=?
                WHERE position_id=?""";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            bind(ps, p);
            ps.setLong(13, p.positionId());
            ps.executeUpdate();
        }
    }

    /**
     * Persists a stable controller-assigned id. This is used only by real
     * automation checkpoints so a crash cannot separate the in-memory and
     * durable identity while waiting for an asynchronous generated key.
     */
    public void upsert(Position p) throws SQLException {
        if (p == null || p.positionId() <= 0) {
            throw new IllegalArgumentException("upsert requires a stable positive position id");
        }
        String sql = """
                INSERT INTO positions
                (position_id, mode, item_key, stack_bucket, quantity, purchase_price, target_price,
                 purchased_at, listed_at, closed_at, sale_price, realized_profit, status, client, loan_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(position_id) DO UPDATE SET
                    mode=excluded.mode,
                    item_key=excluded.item_key,
                    stack_bucket=excluded.stack_bucket,
                    quantity=excluded.quantity,
                    purchase_price=excluded.purchase_price,
                    target_price=excluded.target_price,
                    purchased_at=excluded.purchased_at,
                    listed_at=excluded.listed_at,
                    closed_at=excluded.closed_at,
                    sale_price=excluded.sale_price,
                    realized_profit=excluded.realized_profit,
                    status=excluded.status,
                    client=excluded.client,
                    loan_id=CASE WHEN excluded.loan_id > 0 THEN excluded.loan_id ELSE positions.loan_id END
                WHERE positions.mode=excluded.mode
                  AND positions.item_key=excluded.item_key
                  AND positions.stack_bucket=excluded.stack_bucket
                  AND positions.quantity=excluded.quantity
                  AND positions.purchase_price=excluded.purchase_price
                  AND positions.purchased_at=excluded.purchased_at""";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, p.positionId());
            bind(ps, p, 2);
            ps.setString(14, client);
            ps.setLong(15, loanId);
            if (ps.executeUpdate() != 1) {
                throw new SQLException(
                        "position id conflicts with a different immutable position identity");
            }
        }
    }

    /**
     * Atomically saves the real position (when present) and the singleton
     * automation-session counters/intention row.
     */
    public void saveAutomationCheckpoint(AutomationSessionCheckpoint checkpoint)
            throws SQLException {
        if (checkpoint == null) throw new IllegalArgumentException("checkpoint is required");
        ConnectionState transaction = beginTransaction();
        try {
            if (checkpoint.position() != null) upsert(checkpoint.position());
            upsertAutomationState(checkpoint);
            transaction.commit(db.connection());
        } catch (SQLException | RuntimeException e) {
            transaction.rollback(db.connection(), e);
            throw e;
        } finally {
            transaction.restore(db.connection());
        }
    }

    /**
     * Loads durable real exposure and reconstructs caps conservatively from
     * every REAL position created since the persisted session start.
     */
    public AutomationSessionRecovery loadAutomationRecovery() throws SQLException {
        List<Position> open = findOpen("REAL");
        AutomationSessionRecovery stored = readAutomationState(open);
        if (stored == null) {
            if (open.isEmpty()) return AutomationSessionRecovery.empty();
            long spend = 0;
            long startedAt = 0;
            for (Position position : open) {
                spend = saturatedAdd(spend, Math.max(0, position.purchasePrice()));
                if (position.purchasedAt() > 0
                        && (startedAt == 0 || position.purchasedAt() < startedAt)) {
                    startedAt = position.purchasedAt();
                }
            }
            return new AutomationSessionRecovery(true, "UNKNOWN", "PAUSED",
                    open.size(), spend, startedAt, 0, 0, System.currentTimeMillis(),
                    "Legacy unresolved REAL position recovered without session metadata",
                    "", AutomationUncertainExposure.none(), open);
        }

        int reconstructedTrades = 0;
        long reconstructedSpend = 0;
        for (Position position : findAll("REAL")) {
            if (!belongsToSession(position, stored.sessionStartedAt())) continue;
            if (position.status() == PositionStatus.SIGNAL
                    || position.status() == PositionStatus.MISSED_BEFORE_PURCHASE) continue;
            reconstructedTrades++;
            // Committed spend is money still out, not everything ever spent:
            // a closed position has already returned its purchase price.
            if (position.status().isClosed()) continue;
            reconstructedSpend = saturatedAdd(
                    reconstructedSpend, Math.max(0, position.purchasePrice()));
        }
        // A durable pre-command BUY intent has no Position yet. On restart we
        // cannot know whether the command completed after the last observed
        // frame, so charge it against both caps until the player explicitly
        // resolves the exposure. LIST uncertainty already has a purchased
        // Position and must not be counted twice.
        AutomationUncertainExposure uncertain = stored.uncertainExposure();
        if (uncertain.active()
                && uncertain.phase().toUpperCase(java.util.Locale.ROOT).contains("BUY")) {
            reconstructedTrades++;
            reconstructedSpend = saturatedAdd(
                    reconstructedSpend, uncertain.buyPrice());
        }
        boolean mustRemainOpen = stored.sessionOpen() || stored.unresolvedExposure();
        return new AutomationSessionRecovery(
                mustRemainOpen,
                stored.runMode(),
                stored.controllerState(),
                Math.max(stored.tradesStarted(), reconstructedTrades),
                Math.max(stored.committedSpend(), reconstructedSpend),
                stored.sessionStartedAt(),
                stored.sessionActiveMillis(),
                stored.lifetimeActiveMillis(),
                stored.updatedAt(),
                stored.detail(),
                stored.boundListingKey(),
                stored.uncertainExposure(),
                open);
    }

    /**
     * Atomically records the two-step audit and resolves exactly one recovered
     * exposure. It cannot close the containing session or reset its caps.
     */
    public void resolveAutomationExposure(AutomationManualResolution resolution,
                                          AutomationSessionCheckpoint checkpoint)
            throws SQLException {
        if (resolution == null || checkpoint == null) {
            throw new IllegalArgumentException("resolution and checkpoint are required");
        }
        if (!checkpoint.sessionOpen()) {
            throw new IllegalArgumentException(
                    "manual exposure resolution cannot close the recovered session");
        }
        if (checkpoint.uncertainExposure().active()) {
            throw new IllegalArgumentException(
                    "resolved checkpoint must clear uncertain intent");
        }

        ConnectionState transaction = beginTransaction();
        try {
            AutomationSessionRecovery current = loadAutomationRecovery();
            if (!current.unresolvedExposure()) {
                throw new IllegalStateException("no recovered exposure remains to resolve");
            }
            // Committed spend may legitimately fall: a parked listing that
            // sells returns its purchase price to the budget. The trade count
            // is a one-way cap - it must never decrease. But a resolution that
            // arrives carrying a lower count (a fresh session start reset the
            // in-memory counter while this exposure was still pending, or a
            // force-reset diverged from the on-disk recovery) must not hard-fail
            // the whole automation over a bookkeeping figure - that leaves the
            // session PAUSED on "persistence FAILED" until a restart. Clamp the
            // written count up to the recovered value: the cap is preserved and
            // the resolution still lands.
            AutomationSessionCheckpoint toWrite = checkpoint;
            if (checkpoint.tradesStarted() < current.tradesStarted()) {
                System.getLogger("dev.doughbay.storage.PositionRepository").log(
                        System.Logger.Level.WARNING,
                        "Manual resolution arrived with " + checkpoint.tradesStarted()
                                + " trades started, below the recovered cap of " + current.tradesStarted()
                                + "; clamping up to the recovered count rather than failing persistence");
                toWrite = new AutomationSessionCheckpoint(
                        checkpoint.sessionOpen(), checkpoint.runMode(), checkpoint.controllerState(),
                        current.tradesStarted(), checkpoint.committedSpend(), checkpoint.sessionStartedAt(),
                        checkpoint.sessionActiveMillis(), checkpoint.lifetimeActiveMillis(),
                        checkpoint.updatedAt(), checkpoint.detail(), checkpoint.boundListingKey(),
                        checkpoint.position(), checkpoint.uncertainExposure());
            }

            ResolutionSubject subject = resolutionSubject(current, resolution.positionId());
            if (resolution.positionId() > 0) {
                Position closed = checkpoint.position();
                if (closed == null || closed.positionId() != resolution.positionId()
                        || !closed.status().isClosed()) {
                    throw new IllegalArgumentException(
                            "position resolution requires the same position in a closed state");
                }
                upsert(closed);
            } else if (checkpoint.position() != null
                    && !checkpoint.position().status().isClosed()) {
                throw new IllegalArgumentException(
                        "uncertain-intent resolution cannot introduce open exposure");
            }
            insertResolutionAudit(resolution, subject);
            upsertAutomationState(toWrite);
            transaction.commit(db.connection());
        } catch (SQLException | RuntimeException e) {
            transaction.rollback(db.connection(), e);
            throw e;
        } finally {
            transaction.restore(db.connection());
        }
    }

    public List<Position> findOpen(String mode) throws SQLException {
        String sql = "SELECT * FROM positions WHERE mode=? AND status NOT IN (" + CLOSED_STATUSES + ")";
        if (client.isEmpty()) return query(sql, mode);
        // Positions from before this column have no owner. They are not
        // claimed: with two clients running, whichever loaded first would take
        // the other's listings and then try to reprice what it cannot see.
        // Both clients read them, as they did before ownership existed, and
        // they drain as they sell; everything bought from now on is stamped.
        return queryForClient(sql + " AND (client=? OR client='')", mode);
    }

    /**
     * Positions this client cancelled recently and never sold.
     *
     * <p>For a listing that is up on the auction house with no open position
     * behind it. That happens: the slot audit read one page of a two-page
     * book for days, decided everything on page two had gone, and closed those
     * positions while the listings stayed live. The stock is real and the
     * money is real; only the record went missing, and without it nothing
     * reprices or pulls the stack back and the slot is held for ever.
     *
     * <p>Only cancellations are offered back, never sales - a sold position
     * has money against it and reopening one would invent stock that is gone.
     * Newest first, so a stack listed twice reattaches to the more recent try.
     */
    public List<Position> findRecentlyCancelled(String mode, long since) throws SQLException {
        String where = "SELECT * FROM positions WHERE mode=? AND status='CANCELLED' AND closed_at>=" + since
                + " AND COALESCE(realized_profit,0)=0";
        String tail = " ORDER BY closed_at DESC LIMIT 500";
        if (client.isEmpty()) return query(where + tail, mode);
        return queryForClient(where + " AND (client=? OR client='')" + tail, mode);
    }

    private List<Position> queryForClient(String sql, String mode) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, mode);
            ps.setString(2, client);
            try (ResultSet rs = ps.executeQuery()) {
                return read(rs);
            }
        }
    }

    /**
     * Open listings per client: who in the hive is holding what, and what it
     * cost them. Each account has its own auction and its own slots, so this
     * is the only way to see the whole book in one place.
     */
    public java.util.Map<String, long[]> listedByClient(String mode) throws SQLException {
        java.util.Map<String, long[]> out = new java.util.LinkedHashMap<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT client, COUNT(*), COALESCE(SUM(purchase_price),0), COALESCE(SUM(target_price),0) "
                        + "FROM positions WHERE mode=? AND status='LISTED' GROUP BY client ORDER BY 2 DESC")) {
            ps.setString(1, mode);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String who = rs.getString(1);
                    out.put(who == null || who.isBlank() ? "(unclaimed)" : who,
                            new long[] {rs.getLong(2), rs.getLong(3), rs.getLong(4)});
                }
            }
        }
        return out;
    }

    /**
     * Realized profit and settled-sale count per market for real trades that
     * closed at or after {@code since}, so selection can judge a market on its
     * recent record rather than its whole history. Applies the same settlement
     * rule the performance report uses for REAL mode: a genuine SOLD position
     * with a real sale price and a finite profit.
     *
     * <p>Windowed on purpose. A market's fortunes move — cheap to flip today,
     * tight tomorrow — so a loss counted forever would ban a market that has
     * since recovered. Reading only the recent window lets an old loss age out
     * and a market flow back in on its own.
     */
    public List<dev.doughbay.core.performance.MarketProfit> realizedProfitByItemSince(long since)
            throws SQLException {
        String sql = """
                SELECT item_key, COALESCE(SUM(realized_profit), 0) AS profit, COUNT(*) AS sales
                FROM positions
                WHERE mode = 'REAL' AND status = 'SOLD'
                  AND closed_at >= ? AND closed_at >= purchased_at
                  AND sale_price > 0 AND realized_profit IS NOT NULL
                GROUP BY item_key""";
        List<dev.doughbay.core.performance.MarketProfit> out = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new dev.doughbay.core.performance.MarketProfit(
                            rs.getString("item_key"), rs.getDouble("profit"), rs.getInt("sales")));
                }
            }
        }
        return out;
    }

    public List<Position> findAll(String mode) throws SQLException {
        return query("SELECT * FROM positions WHERE mode=?", mode);
    }

    private List<Position> query(String sql, String mode) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, mode);
            try (ResultSet rs = ps.executeQuery()) {
                return read(rs);
            }
        }
    }

    private List<Position> read(ResultSet rs) throws SQLException {
        {
            {
                List<Position> positions = new ArrayList<>();
                while (rs.next()) {
                    double profit = rs.getDouble("realized_profit");
                    if (rs.wasNull()) profit = Double.NaN;
                    positions.add(new Position(
                            rs.getLong("position_id"),
                            rs.getString("mode"),
                            rs.getString("item_key"),
                            bucketFromLabel(rs.getString("stack_bucket")),
                            rs.getInt("quantity"),
                            rs.getLong("purchase_price"),
                            rs.getLong("target_price"),
                            rs.getLong("purchased_at"),
                            rs.getLong("listed_at"),
                            rs.getLong("closed_at"),
                            rs.getLong("sale_price"),
                            profit,
                            PositionStatus.valueOf(rs.getString("status"))));
                }
                return positions;
            }
        }
    }

    private static StackBucket bucketFromLabel(String label) {
        for (StackBucket b : StackBucket.values()) {
            if (b.label().equals(label)) return b;
        }
        return StackBucket.OTHER;
    }

    private static void bind(PreparedStatement ps, Position p) throws SQLException {
        bind(ps, p, 1);
    }

    private static void bind(PreparedStatement ps, Position p, int start) throws SQLException {
        ps.setString(start, p.mode());
        ps.setString(start + 1, p.itemKey());
        ps.setString(start + 2, p.bucket().label());
        ps.setInt(start + 3, p.quantity());
        ps.setLong(start + 4, p.purchasePrice());
        ps.setLong(start + 5, p.targetPrice());
        ps.setLong(start + 6, p.purchasedAt());
        ps.setLong(start + 7, p.listedAt());
        ps.setLong(start + 8, p.closedAt());
        ps.setLong(start + 9, p.salePrice());
        if (Double.isNaN(p.realizedProfit())) {
            ps.setNull(start + 10, java.sql.Types.REAL);
        } else {
            ps.setDouble(start + 10, p.realizedProfit());
        }
        ps.setString(start + 11, p.status().name());
    }

    private void upsertAutomationState(AutomationSessionCheckpoint checkpoint)
            throws SQLException {
        String sql = """
                INSERT INTO automation_session_state
                (client, session_open, run_mode, controller_state, trades_started,
                 committed_spend, session_started_at, session_active_millis,
                 lifetime_active_millis, updated_at, detail, bound_listing_key,
                 uncertain_active, uncertain_phase, uncertain_listing_key, uncertain_item_key,
                 uncertain_item_id, uncertain_item_count, uncertain_buy_price,
                 uncertain_target_price, uncertain_seller, uncertain_observed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(client) DO UPDATE SET
                    session_open=excluded.session_open,
                    run_mode=excluded.run_mode,
                    controller_state=excluded.controller_state,
                    trades_started=excluded.trades_started,
                    committed_spend=excluded.committed_spend,
                    session_active_millis=CASE
                        WHEN automation_session_state.session_started_at=excluded.session_started_at
                        THEN MAX(automation_session_state.session_active_millis,
                                 excluded.session_active_millis)
                        ELSE excluded.session_active_millis
                    END,
                    lifetime_active_millis=MAX(
                        automation_session_state.lifetime_active_millis,
                        excluded.lifetime_active_millis),
                    session_started_at=excluded.session_started_at,
                    updated_at=excluded.updated_at,
                    detail=excluded.detail,
                    bound_listing_key=excluded.bound_listing_key,
                    uncertain_active=excluded.uncertain_active,
                    uncertain_phase=excluded.uncertain_phase,
                    uncertain_listing_key=excluded.uncertain_listing_key,
                    uncertain_item_key=excluded.uncertain_item_key,
                    uncertain_item_id=excluded.uncertain_item_id,
                    uncertain_item_count=excluded.uncertain_item_count,
                    uncertain_buy_price=excluded.uncertain_buy_price,
                    uncertain_target_price=excluded.uncertain_target_price,
                    uncertain_seller=excluded.uncertain_seller,
                    uncertain_observed_at=excluded.uncertain_observed_at""";
        AutomationUncertainExposure uncertain = checkpoint.uncertainExposure();
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, client);
            ps.setInt(2, checkpoint.sessionOpen() ? 1 : 0);
            ps.setString(3, checkpoint.runMode());
            ps.setString(4, checkpoint.controllerState());
            ps.setInt(5, checkpoint.tradesStarted());
            ps.setLong(6, checkpoint.committedSpend());
            ps.setLong(7, checkpoint.sessionStartedAt());
            ps.setLong(8, checkpoint.sessionActiveMillis());
            ps.setLong(9, checkpoint.lifetimeActiveMillis());
            ps.setLong(10, checkpoint.updatedAt());
            ps.setString(11, checkpoint.detail());
            ps.setString(12, checkpoint.boundListingKey());
            ps.setInt(13, uncertain.active() ? 1 : 0);
            ps.setString(14, uncertain.phase());
            ps.setString(15, uncertain.listingKey());
            ps.setString(16, uncertain.itemKey());
            ps.setString(17, uncertain.itemId());
            ps.setInt(18, uncertain.itemCount());
            ps.setLong(19, uncertain.buyPrice());
            ps.setLong(20, uncertain.targetPrice());
            ps.setString(21, uncertain.seller());
            ps.setLong(22, uncertain.observedAt());
            ps.executeUpdate();
        }
    }

    private AutomationSessionRecovery readAutomationState(List<Position> open)
            throws SQLException {
        // This client's own checkpoint, or the unowned one left by the
        // migration when it has never written its own. Never another
        // client's: reading their committed spend against this balance is
        // what stopped the second account trading at all.
        String sql = "SELECT * FROM automation_session_state WHERE client=? OR client='' "
                + "ORDER BY (client=?) DESC LIMIT 1";
        try (PreparedStatement st = db.connection().prepareStatement(sql)) {
            st.setString(1, client);
            st.setString(2, client);
            ResultSet rs = st.executeQuery();
            if (!rs.next()) return null;
            AutomationUncertainExposure uncertain = new AutomationUncertainExposure(
                    rs.getInt("uncertain_active") != 0,
                    rs.getString("uncertain_phase"),
                    rs.getString("uncertain_listing_key"),
                    rs.getString("uncertain_item_key"),
                    rs.getString("uncertain_item_id"),
                    rs.getInt("uncertain_item_count"),
                    rs.getLong("uncertain_buy_price"),
                    rs.getLong("uncertain_target_price"),
                    rs.getString("uncertain_seller"),
                    rs.getLong("uncertain_observed_at"));
            return new AutomationSessionRecovery(
                    rs.getInt("session_open") != 0,
                    rs.getString("run_mode"),
                    rs.getString("controller_state"),
                    rs.getInt("trades_started"),
                    rs.getLong("committed_spend"),
                    rs.getLong("session_started_at"),
                    rs.getLong("session_active_millis"),
                    rs.getLong("lifetime_active_millis"),
                    rs.getLong("updated_at"),
                    rs.getString("detail"),
                    rs.getString("bound_listing_key"),
                    uncertain,
                    open);
        }
    }

    private ResolutionSubject resolutionSubject(AutomationSessionRecovery recovery,
                                                long positionId) {
        if (positionId == 0) {
            AutomationUncertainExposure uncertain = recovery.uncertainExposure();
            if (!uncertain.active()) {
                throw new IllegalArgumentException("no uncertain intent is available to resolve");
            }
            return new ResolutionSubject(0, uncertain.phase(), uncertain.itemKey(),
                    uncertain.itemId(), uncertain.itemCount(), uncertain.buyPrice(),
                    uncertain.targetPrice());
        }
        Position position = recovery.openPositions().stream()
                .filter(candidate -> candidate.positionId() == positionId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "selected recovered position is not open"));
        String itemId = position.itemKey();
        int hash = itemId.indexOf('#');
        if (hash >= 0) itemId = itemId.substring(0, hash);
        return new ResolutionSubject(position.positionId(), position.status().name(),
                position.itemKey(), itemId, position.quantity(), position.purchasePrice(),
                position.targetPrice());
    }

    private void insertResolutionAudit(AutomationManualResolution resolution,
                                       ResolutionSubject subject) throws SQLException {
        String sql = """
                INSERT INTO automation_resolution_audit
                (recorded_at, position_id, phase, item_key, item_id, item_count,
                 buy_price, target_price, first_confirmed_at, second_confirmed_at,
                 evidence_observed_at, evidence_summary, resolution_detail)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, resolution.secondConfirmedAt());
            ps.setLong(2, subject.positionId());
            ps.setString(3, subject.phase());
            ps.setString(4, subject.itemKey());
            ps.setString(5, subject.itemId());
            ps.setInt(6, subject.itemCount());
            ps.setLong(7, subject.buyPrice());
            ps.setLong(8, subject.targetPrice());
            ps.setLong(9, resolution.firstConfirmedAt());
            ps.setLong(10, resolution.secondConfirmedAt());
            ps.setLong(11, resolution.evidenceObservedAt());
            ps.setString(12, resolution.evidenceSummary());
            ps.setString(13, resolution.resolutionDetail());
            ps.executeUpdate();
        }
    }

    private record ResolutionSubject(long positionId, String phase, String itemKey,
                                     String itemId, int itemCount, long buyPrice,
                                     long targetPrice) {
    }

    private ConnectionState beginTransaction() throws SQLException {
        boolean autoCommit = db.connection().getAutoCommit();
        if (autoCommit) db.connection().setAutoCommit(false);
        return new ConnectionState(autoCommit);
    }

    private record ConnectionState(boolean restoreAutoCommit) {
        void commit(java.sql.Connection connection) throws SQLException {
            connection.commit();
        }

        void rollback(java.sql.Connection connection, Exception original) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                original.addSuppressed(rollbackFailure);
            }
        }

        void restore(java.sql.Connection connection) throws SQLException {
            if (restoreAutoCommit) connection.setAutoCommit(true);
        }
    }

    private static boolean belongsToSession(Position position, long sessionStartedAt) {
        if (sessionStartedAt <= 0) return !position.status().isClosed();
        return position.purchasedAt() >= sessionStartedAt;
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    /**
     * Returns the last durable REAL-automation active-time counters. This is a
     * database snapshot: a currently running controller may have up to one
     * heartbeat interval of additional in-memory runtime.
     */
    public AutomationRuntimeSummary automationRuntimeSummary() throws SQLException {
        String sql = """
                SELECT session_active_millis, lifetime_active_millis,
                       session_started_at, session_open, updated_at
                FROM automation_session_state WHERE client=? OR client='' 
                ORDER BY (client=?) DESC LIMIT 1""";
        try (PreparedStatement st = db.connection().prepareStatement(sql)) {
            st.setString(1, client);
            st.setString(2, client);
            ResultSet rs = st.executeQuery();
            if (!rs.next()) return AutomationRuntimeSummary.empty();
            return new AutomationRuntimeSummary(
                    rs.getLong("session_active_millis"),
                    rs.getLong("lifetime_active_millis"),
                    rs.getLong("session_started_at"),
                    rs.getInt("session_open") != 0,
                    rs.getLong("updated_at"));
        }
    }

    public void recordBalance(long recordedAt, String mode, long balance, long inventoryValue)
            throws SQLException {
        String sql = "INSERT INTO balance_history (recorded_at, mode, balance, inventory_value) VALUES (?,?,?,?)";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setLong(1, recordedAt);
            ps.setString(2, mode);
            ps.setLong(3, balance);
            ps.setLong(4, inventoryValue);
            ps.executeUpdate();
        }
    }

    public record BalancePoint(long recordedAt, long balance, long inventoryValue) {
    }

    public List<BalancePoint> balanceHistory(String mode) throws SQLException {
        String sql = "SELECT recorded_at, balance, inventory_value FROM balance_history WHERE mode=? ORDER BY recorded_at";
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            ps.setString(1, mode);
            try (ResultSet rs = ps.executeQuery()) {
                List<BalancePoint> points = new ArrayList<>();
                while (rs.next()) {
                    points.add(new BalancePoint(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
                }
                return points;
            }
        }
    }

    /**
     * Loads one account mode and computes its deterministic lifetime report.
     * Callers should run this database-backed method off the Minecraft render
     * thread, just like the other repository operations.
     */
    public PerformanceStats performance(PerformanceMode mode) throws SQLException {
        if (mode == null) throw new IllegalArgumentException("mode is required");
        // balance_history has no account/session identity and PAPER CLI runs
        // may restart from a configured bankroll. It must not be combined into
        // a lifetime-equity claim. The report's deployed/profit series remains
        // reconstructible from immutable position timestamps.
        PerformanceStats.RuntimeSummary runtime = PerformanceStats.RuntimeSummary.empty();
        if (mode == PerformanceMode.REAL) {
            AutomationRuntimeSummary durable = automationRuntimeSummary();
            runtime = new PerformanceStats.RuntimeSummary(
                    durable.currentSessionActiveMillis(),
                    durable.lifetimeActiveMillis(),
                    durable.sessionStartedAt(),
                    durable.sessionOpen(),
                    durable.recordedAt());
        }
        return PerformanceStats.compute(mode, findAll(mode.name()), List.of(), runtime);
    }
}

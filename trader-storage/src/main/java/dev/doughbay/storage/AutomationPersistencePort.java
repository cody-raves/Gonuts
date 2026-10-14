package dev.doughbay.storage;

/**
 * Non-blocking boundary used by the Minecraft controller. Implementations
 * must never perform database work in {@link #submit} or {@link #status}.
 */
public interface AutomationPersistencePort extends AutoCloseable {

    /**
     * The hive loan paying for the trade being opened, or 0 for this client's
     * own money. Stamped onto the position so the repayment can find the trade
     * the loan actually bought, wait for it to sell, and share its profit.
     */
    default void setLoanId(long loanId) {
    }


    /**
     * Asks, off this thread, for positions cancelled since {@code since} and
     * never sold. For a listing that is up with no open position behind it:
     * the record is what went missing, and it is the record that says what the
     * stack cost. Answered later in {@link #cancelledSnapshot()}.
     */
    default void requestCancelledSnapshot(long since) {
    }

    /** The last answer to {@link #requestCancelledSnapshot}; empty until one arrives. */
    default java.util.List<dev.doughbay.core.model.Position> cancelledSnapshot() {
        return java.util.List.of();
    }

    /**
     * The account this port writes as, or "" when the ledger has one owner.
     * Position ids are the ledger's global primary key, so a client sharing a
     * ledger has to know which account it mints them for.
     */
    default String client() {
        return "";
    }

    /** In-memory ports use process-unique IDs; the SQLite worker overrides this with durable leases. */
    default long allocatePositionId() {
        return InMemoryIds.NEXT.getAndIncrement();
    }

    default boolean positionIdsAvailable() { return true; }

    final class InMemoryIds {
        private InMemoryIds() { }
        private static final java.util.concurrent.atomic.AtomicLong NEXT =
                new java.util.concurrent.atomic.AtomicLong(1L << 49);
    }

    enum LoadState { NOT_STARTED, LOADING, READY, FAILED, CLOSED }

    record Status(LoadState loadState,
                  AutomationSessionRecovery recovery,
                  long lastAcceptedGeneration,
                  long lastDurableGeneration,
                  String detail,
                  long lastRefusedGeneration,
                  java.util.Set<Long> refusedGenerations) {
        public Status {
            loadState = loadState == null ? LoadState.FAILED : loadState;
            recovery = recovery == null ? AutomationSessionRecovery.empty() : recovery;
            detail = detail == null ? "" : detail;
            lastRefusedGeneration = Math.max(0, lastRefusedGeneration);
            refusedGenerations = refusedGenerations == null ? java.util.Set.of() : java.util.Set.copyOf(refusedGenerations);
            if (lastAcceptedGeneration < 0 || lastDurableGeneration < 0
                    || lastDurableGeneration > lastAcceptedGeneration) {
                throw new IllegalArgumentException("invalid persistence generations");
            }
        }

        public Status(LoadState loadState, AutomationSessionRecovery recovery,
                      long lastAcceptedGeneration, long lastDurableGeneration,
                      String detail, long lastRefusedGeneration) {
            this(loadState, recovery, lastAcceptedGeneration, lastDurableGeneration, detail,
                    lastRefusedGeneration, lastRefusedGeneration > 0
                            ? java.util.Set.of(lastRefusedGeneration) : java.util.Set.of());
        }

        /** Source-compatible constructor for callers that report no refusal. */
        public Status(LoadState loadState,
                      AutomationSessionRecovery recovery,
                      long lastAcceptedGeneration,
                      long lastDurableGeneration,
                      String detail) {
            this(loadState, recovery, lastAcceptedGeneration, lastDurableGeneration, detail, 0);
        }

        public boolean ready() {
            return loadState == LoadState.READY;
        }

        public boolean durable(long generation) {
            return generation > 0 && ready() && lastDurableGeneration >= generation
                    && !refusedGenerations.contains(generation);
        }

        /**
         * The ledger rejected this write: the row was refused, the transaction
         * rolled back, and nothing it described was recorded. It will never
         * become durable, so a caller waiting on it must stop waiting.
         */
        public boolean refused(long generation) {
            return generation > 0 && refusedGenerations.contains(generation);
        }

        /**
         * A refusal stays latched until explicit audited reconciliation or worker restart.
         * An unrelated queued success cannot clear it.
         */
        public boolean refusalOutstanding() {
            return lastRefusedGeneration > 0;
        }
    }

    /** Starts background loading; it must not open SQLite on the caller. */
    void start();

    /**
     * Enqueues one immutable checkpoint and returns its generation, or zero
     * when it was rejected. Risky server actions must wait for durable(gen).
     */
    long submit(AutomationSessionCheckpoint checkpoint);

    /** Persist a retail plan, or atomically split its parent into the checkpoint's
     * single and a remainder. No inventory or listing action may outrun durability. */
    default long submitRetail(AutomationSessionCheckpoint checkpoint, RetailLot lot,
                              dev.doughbay.core.model.Position parent,
                              dev.doughbay.core.model.Position remainder) { return 0; }

    default java.util.Map<Long, RetailLot> retailLots() { return java.util.Map.of(); }

    /**
     * Enqueues an audited two-step manual recovery resolution. The supplied
     * checkpoint must retain the open-session counters and close only the
     * explicitly selected exposure.
     */
    long submitManualResolution(AutomationManualResolution resolution,
                                AutomationSessionCheckpoint checkpoint);

    /** Lock-free immutable status/recovery view. */
    Status status();

    /** Signals daemon shutdown without doing database work on the caller. */
    @Override
    void close();
}

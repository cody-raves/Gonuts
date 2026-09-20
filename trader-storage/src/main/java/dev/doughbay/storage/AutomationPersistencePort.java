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

    enum LoadState { NOT_STARTED, LOADING, READY, FAILED, CLOSED }

    record Status(LoadState loadState,
                  AutomationSessionRecovery recovery,
                  long lastAcceptedGeneration,
                  long lastDurableGeneration,
                  String detail) {
        public Status {
            loadState = loadState == null ? LoadState.FAILED : loadState;
            recovery = recovery == null ? AutomationSessionRecovery.empty() : recovery;
            detail = detail == null ? "" : detail;
            if (lastAcceptedGeneration < 0 || lastDurableGeneration < 0
                    || lastDurableGeneration > lastAcceptedGeneration) {
                throw new IllegalArgumentException("invalid persistence generations");
            }
        }

        public boolean ready() {
            return loadState == LoadState.READY;
        }

        public boolean durable(long generation) {
            return generation > 0 && ready() && lastDurableGeneration >= generation;
        }
    }

    /** Starts background loading; it must not open SQLite on the caller. */
    void start();

    /**
     * Enqueues one immutable checkpoint and returns its generation, or zero
     * when it was rejected. Risky server actions must wait for durable(gen).
     */
    long submit(AutomationSessionCheckpoint checkpoint);

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

package dev.doughbay.storage;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dedicated SQLite owner for durable real-automation checkpoints.
 *
 * <p>The Minecraft/render thread only performs bounded queue offers and
 * AtomicReference reads. Database construction, migration, recovery, writes,
 * and close all run on this daemon.
 */
public final class AutomationPersistenceWorker implements AutomationPersistencePort {
    private static final int QUEUE_CAPACITY = 128;

    private final Path databasePath;
    private final BlockingQueue<Command> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong nextGeneration = new AtomicLong();
    private final AtomicReference<Status> status = new AtomicReference<>(new Status(
            LoadState.NOT_STARTED, AutomationSessionRecovery.empty(), 0, 0,
            "Automation recovery has not started"));
    private volatile boolean running;
    private Thread thread;

    /**
     * The account this worker writes as. Several clients may share one ledger,
     * and each owns only what it bought: a listing sits in one account's
     * auction and no other client can touch it.
     */
    private volatile String client = "";

    public void setClient(String name) {
        this.client = name == null ? "" : name;
    }

    /** The hive loan paying for whatever is being bought now, or 0. */
    private volatile long loanId;

    public void setLoanId(long id) {
        this.loanId = Math.max(0, id);
    }

    /** Cancelled positions the controller may want to reopen; read off-thread. */
    private volatile List<dev.doughbay.core.model.Position> cancelled = List.of();

    @Override
    public void requestCancelledSnapshot(long since) {
        if (!running) return;
        queue.offer(new FindCancelled(since));
    }

    @Override
    public List<dev.doughbay.core.model.Position> cancelledSnapshot() {
        return cancelled;
    }

    public AutomationPersistenceWorker(Path databasePath) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath");
    }

    @Override
    public synchronized void start() {
        if (running || thread != null) return;
        running = true;
        status.set(new Status(LoadState.LOADING, AutomationSessionRecovery.empty(),
                0, 0, "Loading durable automation state"));
        thread = new Thread(this::runLoop, "DoughBay-Automation-Persistence");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public synchronized long submit(AutomationSessionCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Status before = status.get();
        if (!running || !before.ready()) return 0;
        long generation = nextGeneration.incrementAndGet();
        if (!queue.offer(new Save(generation, checkpoint))) return 0;
        status.updateAndGet(current -> current.ready()
                ? new Status(current.loadState(), current.recovery(), generation,
                current.lastDurableGeneration(), "Checkpoint queued")
                : current);
        return generation;
    }

    @Override
    public synchronized long submitManualResolution(
            AutomationManualResolution resolution,
            AutomationSessionCheckpoint checkpoint) {
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(checkpoint, "checkpoint");
        Status before = status.get();
        if (!running || !before.ready()) return 0;
        long generation = nextGeneration.incrementAndGet();
        if (!queue.offer(new Resolve(generation, resolution, checkpoint))) return 0;
        status.updateAndGet(current -> current.ready()
                ? new Status(current.loadState(), current.recovery(), generation,
                current.lastDurableGeneration(), "Manual resolution queued")
                : current);
        return generation;
    }

    @Override
    public Status status() {
        return status.get();
    }

    @Override
    public synchronized void close() {
        if (!running && thread == null) return;
        running = false;
        // Wake the daemon; it owns SQLite close and never delegates it back to
        // the Minecraft lifecycle callback.
        queue.offer(Stop.INSTANCE);
    }

    private void runLoop() {
        long accepted = 0;
        long durable = 0;
        AutomationSessionRecovery recovery = AutomationSessionRecovery.empty();
        try (Database database = new Database(databasePath)) {
            PositionRepository positions = new PositionRepository(database);
            positions.setClient(client);
            positions.setLoanId(loanId);
            recovery = positions.loadAutomationRecovery();
            status.set(new Status(LoadState.READY, recovery, 0, 0,
                    recovery.unresolvedExposure()
                            ? "Recovered unresolved real automation exposure"
                            : recovery.sessionOpen()
                            ? "Recovered open automation session counters"
                            : "Automation recovery complete"));
            while (running || !queue.isEmpty()) {
                Command command = queue.poll(250, TimeUnit.MILLISECONDS);
                if (command == null) continue;
                // The owner is fixed for the session; the funding loan is not,
                // so it is read fresh for every write.
                positions.setClient(client);
                positions.setLoanId(loanId);
                if (command == Stop.INSTANCE) {
                    if (!running && queue.isEmpty()) break;
                    continue;
                }
                if (command instanceof FindCancelled find) {
                    try {
                        cancelled = List.copyOf(
                                positions.findRecentlyCancelled("REAL", find.since()));
                    } catch (java.sql.SQLException e) {
                        cancelled = List.of();
                    }
                    continue;
                }
                QueuedWrite write = (QueuedWrite) command;
                accepted = Math.max(accepted, write.generation());
                // A locked file is another writer being slow, not a broken
                // ledger: wait and try again before calling anything failed.
                for (int attempt = 1; ; attempt++) {
                    try {
                        if (write instanceof Save save) {
                            positions.saveAutomationCheckpoint(save.checkpoint());
                        } else if (write instanceof Resolve resolve) {
                            positions.resolveAutomationExposure(
                                    resolve.resolution(), resolve.checkpoint());
                        }
                        break;
                    } catch (java.sql.SQLException e) {
                        String message = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
                        boolean locked = message.contains("locked") || message.contains("busy");
                        if (!locked || attempt >= 12) throw e;
                        Thread.sleep(500L * attempt);
                    }
                }
                durable = write.generation();
                recovery = positions.loadAutomationRecovery();
                long finalAccepted = Math.max(accepted,
                        status.get().lastAcceptedGeneration());
                status.set(new Status(LoadState.READY, recovery,
                        finalAccepted, durable, "Checkpoint durable"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(recovery, accepted, durable,
                    "Automation persistence worker was interrupted");
        } catch (Exception | LinkageError e) {
            fail(recovery, Math.max(accepted, status.get().lastAcceptedGeneration()),
                    durable, "Automation persistence failed: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            running = false;
            Status current = status.get();
            if (current.loadState() != LoadState.FAILED) {
                status.set(new Status(LoadState.CLOSED, current.recovery(),
                        current.lastAcceptedGeneration(), current.lastDurableGeneration(),
                        "Automation persistence worker is closed"));
            }
        }
    }

    private void fail(AutomationSessionRecovery recovery, long accepted,
                      long durable, String detail) {
        status.set(new Status(LoadState.FAILED, recovery,
                Math.max(accepted, durable), durable, detail));
    }

    private sealed interface Command permits QueuedWrite, Stop, FindCancelled {
    }

    private record FindCancelled(long since) implements Command {
    }

    private sealed interface QueuedWrite extends Command permits Save, Resolve {
        long generation();
    }

    private record Save(long generation,
                        AutomationSessionCheckpoint checkpoint) implements QueuedWrite {
    }

    private record Resolve(long generation,
                           AutomationManualResolution resolution,
                           AutomationSessionCheckpoint checkpoint) implements QueuedWrite {
    }

    private enum Stop implements Command { INSTANCE }
}

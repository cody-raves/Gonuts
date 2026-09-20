package dev.doughbay.paper;

import dev.doughbay.core.model.Listing;
import dev.doughbay.core.model.Opportunity;
import dev.doughbay.core.model.Position;
import dev.doughbay.core.model.PositionStatus;
import dev.doughbay.core.model.Sale;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Simulates the strategy against live (or replayed) market data with a
 * simulated bankroll. Strictly chronological: a decision made at time T is
 * never revised by information that arrived after T; later transactions only
 * determine whether the simulated listing would have sold.
 */
public final class PaperTradingEngine {

    public static final String MODE = "PAPER";

    private final PaperConfig config;
    private long balance;
    private long nextLocalId = 1;

    private final Map<Long, Position> positions = new HashMap<>();
    /** Per-position simulation state that is not part of the persisted record. */
    private final Map<Long, PendingState> pending = new HashMap<>();
    private final List<BalancePoint> balanceHistory = new ArrayList<>();
    private Consumer<Position> positionListener = p -> { };

    private record PendingState(String listingKey, long signalPrice, long executeAt,
                                long listingsAhead, int qualifyingSales) {
        PendingState withQualifyingSales(int count) {
            return new PendingState(listingKey, signalPrice, executeAt, listingsAhead, count);
        }
    }

    public record BalancePoint(long at, long balance, long inventoryValue) {
    }

    public PaperTradingEngine(PaperConfig config) {
        this.config = config;
        this.balance = config.startingBalance();
    }

    /** Called with every position create/update, e.g. to persist it. */
    public void setPositionListener(Consumer<Position> listener) {
        this.positionListener = listener;
    }

    public long balance() {
        return balance;
    }

    public Collection<Position> positions() {
        return positions.values();
    }

    public int openPositionCount() {
        return (int) positions.values().stream().filter(p -> !p.status().isClosed()).count();
    }

    /**
     * Registers a signal. The simulated purchase happens only after the
     * configured execution delay, and only if the listing is still available
     * then (checked in {@link #tick}).
     */
    public Position onOpportunity(Opportunity opportunity, long now) {
        long cost = opportunity.buyPrice();
        Position position = new Position(
                nextLocalId++, MODE,
                opportunity.listing().itemKey(), opportunity.listing().bucket(),
                opportunity.listing().itemCount(),
                cost, opportunity.recommendedSellPrice(),
                0, 0, 0, 0, Double.NaN,
                PositionStatus.SIGNAL);
        long listingsAhead = Math.max(0, Math.round(
                opportunity.estimatedHoldHours() * opportunity.stats().salesPerHour()) - 1);
        positions.put(position.positionId(), position);
        pending.put(position.positionId(), new PendingState(
                opportunity.listing().listingKey(), cost,
                now + config.executionDelayMillis(), listingsAhead, 0));
        emit(position);
        return position;
    }

    /**
     * Advances the simulation clock: resolves signals whose execution delay has
     * elapsed (against the currently visible order book) and expires stale
     * simulated listings.
     *
     * @param activeListingsByKey current order book, keyed by listing key
     */
    public void tick(long now, Map<String, Listing> activeListingsByKey) {
        for (Position p : List.copyOf(positions.values())) {
            PendingState state = pending.get(p.positionId());
            if (state == null) continue;

            if (p.status() == PositionStatus.SIGNAL && now >= state.executeAt()) {
                Listing current = activeListingsByKey.get(state.listingKey());
                boolean stillAvailable = current != null && current.totalPrice() <= state.signalPrice();
                if (!stillAvailable) {
                    close(p, PositionStatus.MISSED_BEFORE_PURCHASE, now, 0, 0.0);
                } else if (balance < p.purchasePrice()) {
                    close(p, PositionStatus.CANCELLED, now, 0, 0.0);
                } else {
                    balance -= p.purchasePrice();
                    Position purchased = new Position(p.positionId(), p.mode(), p.itemKey(),
                            p.bucket(), p.quantity(), p.purchasePrice(), p.targetPrice(),
                            now, now, 0, 0, Double.NaN, PositionStatus.SIMULATED_LISTED);
                    positions.put(p.positionId(), purchased);
                    emit(purchased);
                }
            } else if (p.status() == PositionStatus.SIMULATED_LISTED
                    && now - p.listedAt() > config.listingDurationMillis()) {
                // Item comes back to (simulated) inventory unsold; profit stays
                // unrealized and the capital is stuck — exactly the failure
                // mode paper trading exists to measure.
                close(p, PositionStatus.EXPIRED, now, 0, Double.NaN);
            }
        }
        recordBalancePoint(now);
    }

    /**
     * Feeds newly observed completed sales. A simulated listing is deemed
     * LIKELY_SOLD once enough comparable sales at or above its target price
     * happened after it was listed to clear the queue ahead of it.
     */
    public void onSales(List<Sale> newSales, long now) {
        if (newSales == null || newSales.isEmpty()) return;
        List<Sale> chronological = new ArrayList<>();
        for (Sale sale : newSales) {
            // The engine is also used by backtests and therefore enforces its
            // own as-of boundary instead of trusting every caller. A future
            // sale must not close a position, release capital, or enable a
            // second decision at an earlier simulated instant.
            if (sale != null && sale.isValid() && sale.soldAt() <= now) {
                chronological.add(sale);
            }
        }
        chronological.sort(Comparator.comparingLong(Sale::soldAt)
                .thenComparing(sale -> sale.transactionHash() == null
                        ? "" : sale.transactionHash()));

        for (Sale sale : chronological) {
            for (Position p : List.copyOf(positions.values())) {
                if (p.status() != PositionStatus.SIMULATED_LISTED) continue;
                if (!qualifies(sale, p)) continue;

                PendingState state = pending.get(p.positionId());
                if (state == null) continue;
                int count = state.qualifyingSales() + 1;
                if (count > state.listingsAhead()) {
                    double netSale = config.fees().netSale(p.targetPrice());
                    balance += Math.round(netSale);
                    close(p, PositionStatus.LIKELY_SOLD, sale.soldAt(),
                            p.targetPrice(), netSale - p.purchasePrice());
                } else {
                    pending.put(p.positionId(), state.withQualifyingSales(count));
                }
            }
        }
    }

    private static boolean qualifies(Sale sale, Position p) {
        if (!sale.itemKey().equals(p.itemKey())) return false;
        if (sale.bucket() != p.bucket()) return false;
        if (sale.soldAt() <= p.listedAt()) return false;   // strictly after listing: no leakage
        double targetUnit = (double) p.targetPrice() / p.quantity();
        return sale.unitPrice() >= targetUnit * 0.99;
    }

    private void close(Position p, PositionStatus status, long at, long salePrice, double profit) {
        Position closed = p.closed(status, at, salePrice, profit);
        positions.put(p.positionId(), closed);
        pending.remove(p.positionId());
        emit(closed);
    }

    private void emit(Position p) {
        positionListener.accept(p);
    }

    private void recordBalancePoint(long now) {
        balanceHistory.add(new BalancePoint(now, balance, inventoryValue()));
    }

    /** Conservative value of capital stuck in unsold simulated inventory. */
    public long inventoryValue() {
        long value = 0;
        for (Position p : positions.values()) {
            if (p.status() == PositionStatus.SIMULATED_LISTED
                    || p.status() == PositionStatus.EXPIRED) {
                value += p.purchasePrice();
            }
        }
        return value;
    }

    public List<BalancePoint> balanceHistory() {
        return List.copyOf(balanceHistory);
    }

    public PaperMetrics metrics(long now) {
        return PaperMetrics.compute(config.startingBalance(), balance, inventoryValue(),
                positions.values(), balanceHistory, now);
    }
}

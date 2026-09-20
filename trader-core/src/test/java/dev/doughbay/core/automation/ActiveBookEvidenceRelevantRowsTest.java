package dev.doughbay.core.automation;

import dev.doughbay.core.model.Listing;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stability judged on the rows a phase depends on, not the whole book.
 *
 * <p>On a bot-traded item the book changes several times a second, so two
 * identical full scans may never occur. Before a buy only the target row has
 * to hold still; around a listing only the local player's own rows do.
 */
class ActiveBookEvidenceRelevantRowsTest {

    private static final String ITEM = "minecraft:ender_pearl";
    private final ActiveBookEvidence evidence = new ActiveBookEvidence();

    @Test
    void otherRowsChurningDoesNotDisturbTheTargetRow() {
        ActiveBookEvidence.Analysis first = evidence.analyze(ITEM, List.of(
                listing("target", 1_000, "alice", 1),
                listing("x", 900, "bob", 1)));
        ActiveBookEvidence.Analysis second = evidence.analyze(ITEM, List.of(
                listing("target", 1_000, "alice", 2),
                listing("y", 950, "carol", 2),
                listing("z", 990, "dave", 2)));

        // The whole book differs, which the full-book rule rejects...
        assertFalse(evidence.consecutiveStable(first, 10, second, 11));
        // ...while the row that will actually be bought is unchanged.
        assertTrue(evidence.consecutiveStable(first, 10, second, 11,
                row -> "target".equals(row.listingKey())));
    }

    @Test
    void theTargetRowChangingIsNotStable() {
        ActiveBookEvidence.Analysis first = evidence.analyze(ITEM, List.of(
                listing("target", 1_000, "alice", 1)));
        ActiveBookEvidence.Analysis second = evidence.analyze(ITEM, List.of(
                listing("target", 1_200, "alice", 2)));
        assertFalse(evidence.consecutiveStable(first, 10, second, 11,
                row -> "target".equals(row.listingKey())));
    }

    @Test
    void theTargetVanishingIsNotStableAgainstItsPresence() {
        ActiveBookEvidence.Analysis first = evidence.analyze(ITEM, List.of(
                listing("target", 1_000, "alice", 1)));
        ActiveBookEvidence.Analysis second = evidence.analyze(ITEM, List.of(
                listing("other", 1_000, "bob", 2)));
        assertFalse(evidence.consecutiveStable(first, 10, second, 11,
                row -> "target".equals(row.listingKey())));
    }

    @Test
    void chronologyStillApplies() {
        ActiveBookEvidence.Analysis first = evidence.analyze(ITEM, List.of(
                listing("target", 1_000, "alice", 1)));
        ActiveBookEvidence.Analysis second = evidence.analyze(ITEM, List.of(
                listing("target", 1_000, "alice", 2)));
        // The second scan must begin after the first completed.
        assertFalse(evidence.consecutiveStable(first, 10, second, 10,
                row -> "target".equals(row.listingKey())));
    }

    private static Listing listing(String key, long price, String seller, long observedAt) {
        return new Listing(key, observedAt,
                "01234567-89ab-4def-8abc-0123456789ab", seller,
                ITEM, ITEM, 16, price, 60_000L);
    }
}

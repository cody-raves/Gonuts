package dev.doughbay.core.automation;

import dev.doughbay.core.model.Listing;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveBookEvidenceTest {
    private final ActiveBookEvidence evidence = new ActiveBookEvidence();

    @Test
    void everyDuplicateKeyFailsClosedEvenWhenRowsLookIdentical() {
        Listing first = listing("same", 2_000, "Seller", 100);
        Listing laterObservation = listing("same", 2_000, "Seller", 200);
        assertFalse(evidence.analyze("minecraft:ender_pearl",
                List.of(first, laterObservation)).valid());

        Listing conflictingPrice = listing("same", 2_001, "Seller", 200);
        assertFalse(evidence.analyze("minecraft:ender_pearl",
                List.of(first, conflictingPrice)).valid());
    }

    @Test
    void stableProofRequiresSecondScanToBeginAfterFirstCompletion() {
        var first = evidence.analyze("minecraft:ender_pearl",
                List.of(listing("one", 2_000, "Seller", 100)));
        var same = evidence.analyze("minecraft:ender_pearl",
                List.of(listing("one", 2_000, "Seller", 200)));
        assertFalse(evidence.consecutiveStable(first, 150, same, 150));
        assertTrue(evidence.consecutiveStable(first, 150, same, 151));
    }

    @Test
    void materialBookChangeCannotProveStableAbsence() {
        var first = evidence.analyze("minecraft:ender_pearl",
                List.of(listing("one", 2_000, "Seller", 100)));
        var changed = evidence.analyze("minecraft:ender_pearl",
                List.of(listing("two", 2_000, "Seller", 200)));
        assertFalse(evidence.consecutiveStable(first, 150, changed, 151));
    }

    private static Listing listing(String key, long price, String seller, long observedAt) {
        return new Listing(key, observedAt,
                "01234567-89ab-4def-8abc-0123456789ab", seller,
                "minecraft:ender_pearl", "minecraft:ender_pearl",
                16, price, 60_000L);
    }
}

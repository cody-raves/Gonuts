package dev.doughbay.core.automation;

import dev.doughbay.core.model.Listing;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveListingReconcilerTest {
    private static final UUID LOCAL = UUID.fromString("01234567-89ab-4def-8abc-0123456789ab");
    private static final ActiveListingReconciler.Target TARGET =
            new ActiveListingReconciler.Target("minecraft:ender_pearl", 16, 2_000);
    private final ActiveListingReconciler reconciler = new ActiveListingReconciler();

    @Test
    void uuidIdentityAndExactDuplicatesAreDeduplicatedByListingKey() {
        Listing own = listing("one", LOCAL.toString(), "LocalPlayer");
        var result = reconciler.reconcile(List.of(own, own), TARGET, LOCAL, "LocalPlayer");
        assertEquals(1, result.ownMatches().size());
        assertTrue(result.uniqueOwn().isPresent());
    }

    @Test
    void nameFallbackAppliesOnlyWhenUuidIsAbsent() {
        Listing missingUuid = listing("one", "", "localplayer");
        assertEquals(ActiveListingReconciler.Ownership.OWN,
                reconciler.ownershipOf(missingUuid, LOCAL, "LocalPlayer"));

        Listing malformedUuid = listing("two", "not-a-uuid", "LocalPlayer");
        assertEquals(ActiveListingReconciler.Ownership.UNKNOWN,
                reconciler.ownershipOf(malformedUuid, LOCAL, "LocalPlayer"));
    }

    @Test
    void multipleOrUnknownExactMatchesAreAmbiguous() {
        Listing one = listing("one", LOCAL.toString(), "LocalPlayer");
        Listing two = listing("two", LOCAL.toString().replace("-", ""), "LocalPlayer");
        assertTrue(reconciler.reconcile(List.of(one, two), TARGET, LOCAL, "LocalPlayer")
                .ambiguous());
        assertTrue(reconciler.reconcile(
                List.of(listing("x", "bad", "LocalPlayer")), TARGET, LOCAL, "LocalPlayer")
                .ambiguous());
    }

    @Test
    void contradictoryUuidAndNameAreUnknownRatherThanOverridden() {
        UUID other = UUID.fromString("11234567-89ab-4def-8abc-0123456789ab");
        Listing contradiction = listing("x", other.toString(), "LocalPlayer");
        assertEquals(ActiveListingReconciler.Ownership.UNKNOWN,
                reconciler.ownershipOf(contradiction, LOCAL, "LocalPlayer"));
    }

    @Test
    void aForeignUuidWithABedrockStyleNameIsSomeoneElseNotUnknown() {
        // Bedrock players arrive through a proxy with names such as ".name",
        // which fail the Java name pattern. A well-formed foreign UUID still
        // settles ownership; "unknown" here blocked every live gate.
        UUID other = UUID.fromString("11234567-89ab-4def-8abc-0123456789ab");
        Listing bedrock = listing("x", other.toString(), ".buzcelik613");
        assertEquals(ActiveListingReconciler.Ownership.OTHER,
                reconciler.ownershipOf(bedrock, LOCAL, "LocalPlayer"));
    }

    @Test
    void reconciliationScanMustStartStrictlyAfterEvidence() {
        assertTrue(ActiveListingReconciler.scanStartedAfterEvidence(1_001, 1_000));
        org.junit.jupiter.api.Assertions.assertFalse(
                ActiveListingReconciler.scanStartedAfterEvidence(1_000, 1_000));
        org.junit.jupiter.api.Assertions.assertFalse(
                ActiveListingReconciler.scanStartedAfterEvidence(999, 1_000));
        org.junit.jupiter.api.Assertions.assertFalse(
                ActiveListingReconciler.scanStartedAfterEvidence(0, 1_000));

        long scanStartedBeforeEvidence = 999;
        long snapshotCompletedAfterEvidence = 1_100;
        assertTrue(snapshotCompletedAfterEvidence > 1_000,
                "regression setup requires a deceptively late completion time");
        org.junit.jupiter.api.Assertions.assertFalse(
                ActiveListingReconciler.scanStartedAfterEvidence(
                        scanStartedBeforeEvidence, 1_000),
                "a late completion cannot turn a pre-evidence scan into proof");
    }

    private static Listing listing(String key, String uuid, String name) {
        return new Listing(key, 1, uuid, name, "minecraft:ender_pearl",
                "minecraft:ender_pearl", 16, 2_000, 60_000L);
    }
}

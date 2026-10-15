package dev.doughbay.fabric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two accounts of a hive share one tuning.json. How many auction and order
 * slots an account's rank grants it is the account's own, so neither may write
 * its number where the other reads it.
 */
class HiveSlotSettingsTest {
    @TempDir Path root;

    @AfterEach void forgetThisClientsFile() {
        Tuning.loadLocalFrom(null);
    }

    @Test
    void slotCountsOfOneAccountSurviveTheOtherSavingItsOwn() throws Exception {
        double auctionBefore = Tuning.get("slots.max");
        double ordersBefore = Tuning.get("orders.bid_slots");
        Path shared = Files.createDirectories(root.resolve("config"));
        Path sharedFile = shared.resolve("tuning.json");
        Path localA = root.resolve("instance-a").resolve("doughbay-local.json");
        Path localB = root.resolve("instance-b").resolve("doughbay-local.json");
        try {
            // Account A has the rank: 45 auction slots, and 30 order slots set by hand.
            Tuning.load(shared);
            Tuning.loadLocalFrom(localA);
            Tuning.set("slots.max", 45);
            Tuning.set("orders.bid_slots", 30);

            // Account B is rankless; the bot reads 18 off its listings page.
            Tuning.loadLocalFrom(localB);
            Tuning.set("slots.max", 18);
            Tuning.set("orders.bid_slots", 12);

            // Account A reads the shared file again and still has its own numbers.
            Tuning.load(shared);
            Tuning.loadLocalFrom(localA);
            assertEquals(45, Tuning.get("slots.max"), "the other account's auction slots overwrote this one's");
            assertEquals(30, Tuning.get("orders.bid_slots"), "the other account's order slots overwrote this one's");

            // Each kept its own numbers in its own file, and neither published them.
            assertTrue(Tuning.thisClientOnly("slots.max"));
            assertEquals(45, read(localA).path("slots.max").asInt());
            assertEquals(30, read(localA).path("orders.bid_slots").asInt());
            assertEquals(18, read(localB).path("slots.max").asInt());
            assertFalse(read(sharedFile).has("slots.max"),
                    "the shared file must not carry an account's own auction slots");
            assertFalse(read(sharedFile).has("orders.bid_slots"),
                    "the shared file must not carry an account's own order slots");
        } finally {
            Tuning.loadLocalFrom(null);
            Tuning.set("slots.max", auctionBefore);
            Tuning.set("orders.bid_slots", ordersBefore);
        }
    }

    private static JsonNode read(Path file) throws Exception {
        return new ObjectMapper().readTree(Files.readString(file));
    }
}

package dev.doughbay.fabric.automation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class RetailOrderPlansTest {
    @TempDir Path temp;
    @Test void anOrderKeepsItsResaleIntentAcrossRestartAndPriceChanges() throws Exception {
        Path file = temp.resolve("retail.txt");
        var plans = new RetailOrderPlans();
        plans.load(file);
        plans.remember("minecraft:diamond", 100, 200);
        plans.remember("minecraft:diamond", 120, 210);
        var restarted = new RetailOrderPlans();
        restarted.load(file);
        assertEquals(200, restarted.target("minecraft:diamond", 100));
        assertEquals(210, restarted.target("minecraft:diamond", 120));
        assertEquals(0, restarted.target("minecraft:diamond", 121));
        assertEquals(0, restarted.target("minecraft:emerald", 100));
    }
    @Test void unreadableOrUnconfiguredStoreCannotAuthorizeAnOrder() throws Exception {
        var plans = new RetailOrderPlans();
        assertThrows(IOException.class, () -> plans.remember("minecraft:diamond", 100, 200));
        Path file = temp.resolve("retail.txt");
        Files.writeString(file, "broken");
        assertThrows(IOException.class, () -> plans.load(file));
        assertThrows(IOException.class, () -> plans.remember("minecraft:diamond", 100, 200));
        assertEquals("broken", Files.readString(file));
    }
}
